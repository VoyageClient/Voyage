/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.mentions

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.epoxy.OnModelBuildFinishedListener
import com.airbnb.mvrx.Incomplete
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.platform.VectorMenuProvider
import im.vector.app.databinding.FragmentMentionsBinding
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.search.SearchFilterSuggestions
import im.vector.app.features.home.room.detail.search.SearchSuggestion
import im.vector.app.features.home.room.detail.search.SearchSuggestionAdapter
import im.vector.app.features.home.room.detail.timeline.tools.setupLiveEmojiInput
import im.vector.app.features.html.PillImageSpan
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import javax.inject.Inject

@AndroidEntryPoint
class MentionsFragment :
        VectorBaseFragment<FragmentMentionsBinding>(),
        MentionsController.Callback,
        VectorMenuProvider {

    @Inject lateinit var controller: MentionsController
    @Inject lateinit var avatarRenderer: AvatarRenderer

    private val viewModel: MentionsViewModel by fragmentViewModel()

    private var searchMenuItem: MenuItem? = null
    private var suggestionAdapter: SearchSuggestionAdapter? = null

    /** Everyone who has mentioned us, so `from:` can complete without a single room to read members from. */
    private var members: List<RoomMemberSummary> = emptyList()

    private val searchEditText: EditText?
        get() = searchMenuItem?.actionView?.findViewById(androidx.appcompat.R.id.search_src_text)

    private val modelBuildListener = OnModelBuildFinishedListener { views.mentionsLoading.isVisible = false }

    private val searchBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            searchMenuItem?.collapseActionView()
        }
    }

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentMentionsBinding {
        return FragmentMentionsBinding.inflate(inflater, container, false)
    }

    override fun getMenuRes() = R.menu.menu_mentions

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        controller.callback = this
        setupToolbar(views.mentionsToolbar)
                .allowBack()
        views.mentionsRecyclerView.configureWith(controller, hasFixedSize = true)
        // Models build asynchronously, so the state turning Success only means the data arrived; the
        // spinner has to stay up until the rows themselves are built.
        controller.addModelBuildListener(modelBuildListener)
        setupSuggestions()
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, searchBackCallback)
    }

    private fun setupSuggestions() {
        val adapter = SearchSuggestionAdapter(avatarRenderer, ::applySuggestion).also { suggestionAdapter = it }
        views.mentionsSuggestions.layoutManager = LinearLayoutManager(requireContext())
        views.mentionsSuggestions.itemAnimator = null
        views.mentionsSuggestions.adapter = adapter
        views.mentionsSuggestions.addItemDecoration(
                BetweenItemsDivider(
                        color = ThemeUtils.getColor(requireContext(), im.vector.lib.ui.styles.R.attr.vctr_list_separator),
                        height = resources.displayMetrics.density.toInt().coerceAtLeast(1),
                )
        )
    }

    override fun handlePostCreateMenu(menu: Menu) {
        val searchItem = menu.findItem(R.id.menu_mentions_search) ?: return
        searchMenuItem = searchItem
        (searchItem.actionView as? SearchView)?.let { searchView ->
            searchView.queryHint = getString(CommonStrings.mentions_search_hint)
            searchView.setupLiveEmojiInput()
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    renderSuggestions(null)
                    return true
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    viewModel.handle(MentionsAction.UpdateSearchQuery(newText.orEmpty()))
                    renderSuggestions(newText)
                    return true
                }
            })
        }
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                searchBackCallback.isEnabled = true
                // The filter keys are worth showing before anything is typed. That is how they get discovered.
                renderSuggestions("")
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                searchBackCallback.isEnabled = false
                showSuggestions(false)
                renderSuggestions(null)
                viewModel.handle(MentionsAction.UpdateSearchQuery(""))
                return true
            }
        })
    }

    // A completed user filter reads as a mention pill: the span only draws over the id, so the term
    // the finder parses is still the plain `from:@user:server`.
    private fun applySuggestion(suggestion: SearchSuggestion) {
        val avatar = suggestion.avatar
        val range = suggestion.pillRange
        val query: CharSequence = if (avatar != null && range != null) {
            SpannableString(suggestion.query).apply {
                val span = PillImageSpan(GlideApp.with(this@MentionsFragment), avatarRenderer, requireContext(), avatar)
                        .also { span -> searchEditText?.let { span.bind(it) } }
                setSpan(span, range.first, range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            suggestion.query
        }
        (searchMenuItem?.actionView as? SearchView)?.setQuery(query, false)
    }

    private fun renderSuggestions(term: String?) {
        val suggestions = term?.let { SearchFilterSuggestions.suggestionsFor(it, members) }.orEmpty()
        // submitList diffs off the main thread, so a list submitted just before the search closed can
        // commit after it: re-read whether the search is still open here rather than trusting `term`.
        suggestionAdapter?.submitList(suggestions) { showSuggestions(suggestions.isNotEmpty()) }
    }

    private fun showSuggestions(hasSuggestions: Boolean) {
        if (view == null) return
        val visible = hasSuggestions && searchMenuItem?.isActionViewExpanded == true
        views.mentionsSuggestions.isVisible = visible
        views.mentionsSuggestionsDivider.isVisible = visible
    }

    // Leaving the screen tears the menu down (it is registered only while RESUMED), so the SearchView
    // vanishes without collapsing, so close the search by hand or its suggestions outlive it.
    override fun onPause() {
        searchBackCallback.isEnabled = false
        searchMenuItem = null
        showSuggestions(false)
        renderSuggestions(null)
        viewModel.handle(MentionsAction.UpdateSearchQuery(""))
        super.onPause()
    }

    override fun onDestroyView() {
        controller.removeModelBuildListener(modelBuildListener)
        views.mentionsRecyclerView.cleanup()
        views.mentionsSuggestions.adapter = null
        suggestionAdapter = null
        controller.callback = null
        searchMenuItem = null
        super.onDestroyView()
    }

    override fun handlePrepareMenu(menu: Menu) {
        withState(viewModel) { state ->
            menu.findItem(R.id.menu_mentions_room).isChecked = state.filter.includeRoomMentions
            menu.findItem(R.id.menu_mentions_keywords).isChecked = state.filter.includeKeywords
            menu.findItem(R.id.menu_mentions_exclude_dms).isChecked = state.filter.excludeDms
        }
    }

    override fun handleMenuItemSelected(item: MenuItem): Boolean = withState(viewModel) { state ->
        when (item.itemId) {
            R.id.menu_mentions_room -> {
                viewModel.handle(MentionsAction.SetIncludeRoomMentions(!state.filter.includeRoomMentions))
                true
            }
            R.id.menu_mentions_keywords -> {
                viewModel.handle(MentionsAction.SetIncludeKeywords(!state.filter.includeKeywords))
                true
            }
            R.id.menu_mentions_exclude_dms -> {
                viewModel.handle(MentionsAction.SetExcludeDms(!state.filter.excludeDms))
                true
            }
            else -> false
        }
    }

    override fun invalidate() = withState(viewModel) { state ->
        if (state.mentions is Incomplete) views.mentionsLoading.isVisible = true
        controller.setData(state)
        if (state.senders != members) {
            members = state.senders
            // The pool arrives after the list loads, so a filter typed before then has to be re-offered.
            if (searchMenuItem?.isActionViewExpanded == true) {
                renderSuggestions((searchMenuItem?.actionView as? SearchView)?.query?.toString().orEmpty())
            }
        }
    }

    override fun onLoadMore() {
        viewModel.loadMore()
    }

    override fun onMentionClicked(roomId: String, eventId: String) {
        navigator.openRoom(
                context = requireContext(),
                roomId = roomId,
                eventId = eventId,
        )
    }
}

/**
 * Draws a separator between rows but never after the last one: the list is clipped at its maximum
 * height, so a trailing divider would either be cut off or double up with the layout's bottom edge.
 */
private class BetweenItemsDivider(color: Int, private val height: Int) : RecyclerView.ItemDecoration() {

    private val paint = Paint().also { it.color = color }

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        outRect.top = if (parent.getChildAdapterPosition(view) > 0) height else 0
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (parent.getChildAdapterPosition(child) <= 0) continue
            canvas.drawRect(
                    child.left.toFloat(), (child.top - height).toFloat(),
                    child.right.toFloat(), child.top.toFloat(), paint
            )
        }
    }
}
