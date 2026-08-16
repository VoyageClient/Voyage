/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.search

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.view.View
import android.widget.EditText
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.airbnb.mvrx.Mavericks
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.addFragment
import im.vector.app.core.glide.GlideApp
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.databinding.ActivitySearchBinding
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.tools.setupLiveEmojiInput
import im.vector.app.features.html.PillImageSpan
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.core.utils.compat.getParcelableCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.members.roomMemberQueryParams
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import javax.inject.Inject

@AndroidEntryPoint
class SearchActivity : VectorBaseActivity<ActivitySearchBinding>() {

    @Inject lateinit var session: Session
    @Inject lateinit var avatarRenderer: AvatarRenderer

    private val searchFragment: SearchFragment?
        get() {
            return supportFragmentManager.findFragmentByTag(FRAGMENT_TAG) as? SearchFragment
        }

    private lateinit var suggestionAdapter: SearchSuggestionAdapter
    private var members: List<RoomMemberSummary> = emptyList()
    private var heightAnimator: ValueAnimator? = null

    private val searchEditText: EditText?
        get() = views.searchView.findViewById(androidx.appcompat.R.id.search_src_text)

    override fun getBinding() = ActivitySearchBinding.inflate(layoutInflater)

    override fun getCoordinatorLayout() = views.coordinatorLayout

    override val rootView: View
        get() = views.coordinatorLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupToolbar(views.searchToolbar)
                .allowBack()
    }

    override fun initUiAndData() {
        val fragmentArgs: SearchArgs = intent?.extras?.getParcelableCompat(Mavericks.KEY_ARG) ?: return
        if (isFirstCreation()) {
            addFragment(views.searchFragmentContainer, SearchFragment::class.java, fragmentArgs, FRAGMENT_TAG)
        }
        setupSuggestions(fragmentArgs.roomId)
        views.searchView.setupLiveEmojiInput()
        views.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                renderSuggestions(null)
                searchFragment?.search(query)
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                renderSuggestions(newText)
                return true
            }
        })
        // Open the keyboard immediately
        views.searchView.requestFocus()
    }

    private fun setupSuggestions(roomId: String) {
        suggestionAdapter = SearchSuggestionAdapter(avatarRenderer, ::applySuggestion)
        views.searchSuggestions.layoutManager = LinearLayoutManager(this)
        // Rows must never animate their own position: the list reveals itself by growing, and a row
        // that also tweens towards its slot slides in from wherever the shorter list had put it.
        (views.searchSuggestions.itemAnimator as? DefaultItemAnimator)?.apply {
            moveDuration = 0
            changeDuration = 0
        }
        views.searchSuggestions.adapter = suggestionAdapter
        views.searchSuggestions.addItemDecoration(
                DividerItemDecoration(this, DividerItemDecoration.VERTICAL).apply {
                    // Theme attrs in a drawable XML don't resolve pre-21, so build the separator here.
                    setDrawable(GradientDrawable().apply {
                        setColor(ThemeUtils.getColor(this@SearchActivity, im.vector.lib.ui.styles.R.attr.vctr_list_separator))
                        setSize(0, resources.displayMetrics.density.toInt().coerceAtLeast(1))
                    })
                }
        )
        // The filter keys are worth showing before anything is typed — that is how they get discovered.
        renderSuggestions("")
        lifecycleScope.launch {
            members = withContext(Dispatchers.Default) {
                session.getRoom(roomId)
                        ?.membershipService()
                        ?.getRoomMembers(roomMemberQueryParams { memberships = listOf(Membership.JOIN) })
                        .orEmpty()
                        .sortedBy { (it.displayName ?: it.userId).lowercase() }
            }
            renderSuggestions(views.searchView.query?.toString())
        }
    }

    // A completed user filter reads as a mention pill: the span only draws over the id, so the term
    // submitted to the search backend is still the plain `from:@user:server`.
    private fun applySuggestion(suggestion: SearchSuggestion) {
        val avatar = suggestion.avatar
        val range = suggestion.pillRange
        val query: CharSequence = if (avatar != null && range != null) {
            SpannableString(suggestion.query).apply {
                val span = PillImageSpan(GlideApp.with(this@SearchActivity), avatarRenderer, this@SearchActivity, avatar)
                        .also { span -> searchEditText?.let { span.bind(it) } }
                setSpan(span, range.first, range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            suggestion.query
        }
        views.searchView.setQuery(query, false)
    }

    private fun renderSuggestions(term: String?) {
        val suggestions = term?.let { SearchFilterSuggestions.suggestionsFor(it, members) }.orEmpty()
        suggestionAdapter.submitList(suggestions) { animateToContentHeight(suggestions.isNotEmpty()) }
    }

    // The list is revealed by growing its own height, never by moving what is inside it: a layout
    // transition on the parent repositions the rows mid-animation, which reads as them flying in.
    private fun animateToContentHeight(hasSuggestions: Boolean) {
        val list = views.searchSuggestions
        if (!hasSuggestions) {
            animateHeight(list, 0)
            return
        }
        if (list.visibility != View.VISIBLE) {
            list.layoutParams.height = 0
            list.visibility = View.VISIBLE
        }
        if (list.width == 0) {
            list.post { animateToContentHeight(hasSuggestions = true) }
            return
        }
        val maxHeight = (MAX_HEIGHT_DP * resources.displayMetrics.density).toInt()
        list.measure(
                View.MeasureSpec.makeMeasureSpec(list.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
        )
        animateHeight(list, list.measuredHeight)
    }

    private fun animateHeight(list: View, target: Int) {
        heightAnimator?.cancel()
        val from = if (list.visibility == View.VISIBLE) list.height else 0
        if (from == target) {
            if (target == 0) list.visibility = View.GONE
            return
        }
        heightAnimator = ValueAnimator.ofInt(from, target).apply {
            duration = ANIMATION_DURATION_MS
            addUpdateListener {
                list.layoutParams.height = it.animatedValue as Int
                list.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (target == 0) list.visibility = View.GONE
                }
            })
            start()
        }
    }

    companion object {
        private const val FRAGMENT_TAG = "SearchFragment"
        private const val ANIMATION_DURATION_MS = 180L
        private const val MAX_HEIGHT_DP = 280

        fun newIntent(context: Context, args: SearchArgs): Intent {
            return Intent(context, SearchActivity::class.java).apply {
                // If we do that we will have the same room two times on the stack. Let's allow infinite stack for the moment.
                // flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra(Mavericks.KEY_ARG, args)
            }
        }
    }
}
