/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.reactions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.airbnb.mvrx.viewModel
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.EmojiCompatFontProvider
import im.vector.app.R
import im.vector.app.core.extensions.observeEvent
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.platform.VectorMenuProvider
import im.vector.app.databinding.ActivityEmojiReactionPickerBinding
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import reactivecircus.flowbinding.android.widget.queryTextChanges
import javax.inject.Inject

/**
 *
 * TODO Loading indicator while getting emoji data source?
 * TODO Finish Refactor to vector base activity
 */
@AndroidEntryPoint
class EmojiReactionPickerActivity :
        VectorBaseActivity<ActivityEmojiReactionPickerBinding>(),
        EmojiCompatFontProvider.FontProviderListener,
        VectorMenuProvider {

    lateinit var viewModel: EmojiChooserViewModel

    override fun getMenuRes() = R.menu.menu_emoji_reaction_picker

    // Finish directly on the toolbar up button. The default routes through the deprecated onBackPressed()
    // dispatch, which drops rapid taps under Android's predictive-back handling.
    override fun handleMenuItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        else -> false
    }

    override fun getBinding() = ActivityEmojiReactionPickerBinding.inflate(layoutInflater)

    override fun getCoordinatorLayout() = views.coordinatorLayout

    override val rootView: View
        get() = views.coordinatorLayout

    override fun getTitleRes() = CommonStrings.title_activity_emoji_reaction_picker

    @Inject lateinit var emojiCompatFontProvider: EmojiCompatFontProvider

    private val searchResultViewModel: EmojiSearchResultViewModel by viewModel()

    override fun initUiAndData() {
        setupToolbar(views.emojiPickerToolbar)
                .allowBack()
        emojiCompatFontProvider.let {
            EmojiDrawView.configureTextPaint(this, it.typeface)
            it.addListener(this)
        }

        viewModel = viewModelProvider.get(EmojiChooserViewModel::class.java)

        viewModel.eventId = intent.getStringExtra(EXTRA_EVENT_ID)
        val roomId = intent.getStringExtra(EXTRA_ROOM_ID)
        searchResultViewModel.handle(EmojiSearchAction.SetRoomId(roomId))
        viewModel.setRoomId(roomId)

        viewModel.navigateEvent.observeEvent(this) {
            if (it == EmojiChooserViewModel.NAVIGATE_FINISH) {
                // finish with result
                val dataResult = Intent()
                dataResult.putExtra(EXTRA_REACTION_RESULT, viewModel.selectedReaction)
                dataResult.putExtra(EXTRA_EVENT_ID, viewModel.eventId)
                setResult(Activity.RESULT_OK, dataResult)
                finish()
            }
        }

        views.emojiPickerWholeListFragmentContainer.isVisible = true
        views.emojiPickerFilteredListFragmentContainer.isVisible = false
    }

    override fun compatibilityFontUpdate(typeface: Typeface?) {
        EmojiDrawView.configureTextPaint(this, typeface)
    }

    override fun onDestroy() {
        emojiCompatFontProvider.removeListener(this)
        super.onDestroy()
    }

    override fun handlePostCreateMenu(menu: Menu) {
        val searchItem = menu.findItem(R.id.search)
        (searchItem.actionView as? SearchView)?.let { searchView ->
            // Fill the toolbar width so the field stretches fully between the search icon and the clear (X).
            searchView.maxWidth = Integer.MAX_VALUE
            searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(p0: MenuItem): Boolean {
                    searchView.isIconified = false
                    searchView.requestFocusFromTouch()
                    // we want to force the tool bar as visible even if hidden with scroll flags
                    views.emojiPickerToolbar.minimumHeight = getActionBarSize()
                    return true
                }

                override fun onMenuItemActionCollapse(p0: MenuItem): Boolean {
                    // when back, clear all search
                    views.emojiPickerToolbar.minimumHeight = 0
                    searchView.setQuery("", true)
                    return true
                }
            })

            searchView.setOnCloseListener {
                currentFocus?.clearFocus()
                searchItem.collapseActionView()
                true
            }

            searchView.queryTextChanges()
                    .debounce(300)
                    .onEach { query ->
                        onQueryText(query.toString())
                    }
                    .launchIn(lifecycleScope)
        }
    }

    // TODO move to ThemeUtils when core module is created
    private fun getActionBarSize(): Int {
        return try {
            val typedValue = TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.actionBarSize, typedValue, true)
            TypedValue.complexToDimensionPixelSize(typedValue.data, resources.displayMetrics)
        } catch (e: Exception) {
            // Timber.e(e, "Unable to get color")
            TypedValue.complexToDimensionPixelSize(56, resources.displayMetrics)
        }
    }

    private fun onQueryText(query: String) {
        if (query.isEmpty()) {
            views.emojiPickerWholeListFragmentContainer.isVisible = true
            views.emojiPickerFilteredListFragmentContainer.isVisible = false
        } else {
            views.emojiPickerWholeListFragmentContainer.isVisible = false
            views.emojiPickerFilteredListFragmentContainer.isVisible = true
            searchResultViewModel.handle(EmojiSearchAction.UpdateQuery(query))
        }
    }

    companion object {

        private const val EXTRA_EVENT_ID = "EXTRA_EVENT_ID"
        private const val EXTRA_ROOM_ID = "EXTRA_ROOM_ID"
        private const val EXTRA_REACTION_RESULT = "EXTRA_REACTION_RESULT"

        fun intent(context: Context, eventId: String, roomId: String? = null): Intent {
            val intent = Intent(context, EmojiReactionPickerActivity::class.java)
            intent.putExtra(EXTRA_EVENT_ID, eventId)
            intent.putExtra(EXTRA_ROOM_ID, roomId)
            return intent
        }

        fun getOutputEventId(data: Intent?): String? = data?.getStringExtra(EXTRA_EVENT_ID)

        fun getOutputReaction(data: Intent?): String? = data?.getStringExtra(EXTRA_REACTION_RESULT)
    }
}
