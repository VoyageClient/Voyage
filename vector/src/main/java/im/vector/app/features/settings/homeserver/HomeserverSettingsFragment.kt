/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.homeserver

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import com.airbnb.epoxy.EpoxyTouchHelper
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.epoxy.ListDividerDecoration
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.platform.VectorMenuProvider
import im.vector.app.core.utils.toast
import im.vector.app.databinding.FragmentGenericRecyclerBinding
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

/**
 * Display some information about the homeserver.
 */
@AndroidEntryPoint
class HomeserverSettingsFragment :
        VectorBaseFragment<FragmentGenericRecyclerBinding>(),
        VectorMenuProvider,
        HomeserverSettingsController.Callback {

    @Inject lateinit var homeserverSettingsController: HomeserverSettingsController

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentGenericRecyclerBinding {
        return FragmentGenericRecyclerBinding.inflate(inflater, container, false)
    }

    private val viewModel: HomeserverSettingsViewModel by fragmentViewModel()

    private val editableUrls = mutableListOf<EditableHomeserverUrl>()

    // The saved list the rows were seeded from, so edits survive unrelated state updates.
    private var seededUrls: List<String>? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        homeserverSettingsController.callback = this
        homeserverSettingsController.editableUrls = editableUrls
        views.genericRecyclerView.configureWith(homeserverSettingsController)
        // Separators are drawn rather than inserted as models, so reordering never shuffles a divider, and
        // they track their row's translation so a dragged row carries its line with it.
        views.genericRecyclerView.addItemDecoration(
                ListDividerDecoration(
                        requireContext(),
                        drawUnder = { it is HomeserverUrlEditItem_ },
                        followItemTranslation = true,
                )
        )
        enableDragReorder()
        view.viewTreeObserver.addOnGlobalLayoutListener(keyboardListener)
    }

    override fun onDestroyView() {
        view?.viewTreeObserver?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                it.removeOnGlobalLayoutListener(keyboardListener)
            } else {
                @Suppress("DEPRECATION")
                it.removeGlobalOnLayoutListener(keyboardListener)
            }
        }
        homeserverSettingsController.callback = null
        views.genericRecyclerView.cleanup()
        super.onDestroyView()
    }

    private var keyboardVisible = false

    /**
     * Drops the caret when the keyboard goes away. Watching the window rather than the back key covers every
     * way it can be dismissed, including the gesture back that never reaches the field on Android 13+.
     */
    private val keyboardListener = ViewTreeObserver.OnGlobalLayoutListener {
        val root = view ?: return@OnGlobalLayoutListener
        val visibleFrame = Rect().also { root.getWindowVisibleDisplayFrame(it) }
        val visible = root.rootView.height - visibleFrame.height() > root.resources.displayMetrics.density * KEYBOARD_MIN_HEIGHT_DP
        if (keyboardVisible && !visible) {
            activity?.currentFocus?.clearFocus()
        }
        keyboardVisible = visible
    }

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.setTitle(CommonStrings.settings_home_server)
    }

    override fun getMenuRes() = R.menu.menu_homeserver_settings

    override fun handlePrepareMenu(menu: Menu) {
        menu.findItem(R.id.homeserverMenuApply)?.apply {
            // seededUrls is null until the saved list arrives; nothing to apply against until then.
            val applicable = seededUrls?.let { it != editedUrls() } == true
            isEnabled = applicable
            val tint = if (applicable) {
                ThemeUtils.getColor(requireContext(), im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
            } else {
                ThemeUtils.getColor(requireContext(), im.vector.lib.ui.styles.R.attr.vctr_content_quaternary)
            }
            icon?.mutate()?.let { DrawableCompat.setTint(it, tint) }
        }
    }

    override fun handleMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.homeserverMenuApply -> { saveUrls(); true }
            else -> false
        }
    }

    override fun retry() {
        viewModel.handle(HomeserverSettingsAction.Refresh)
    }

    override fun refreshHomeserverUrls() {
        viewModel.handle(HomeserverSettingsAction.RefreshHomeserverUrls)
    }

    override fun addHomeserverUrl() {
        syncFromScreen()
        editableUrls.add(EditableHomeserverUrl(""))
        refreshRows()
    }

    override fun deleteHomeserverUrl(url: EditableHomeserverUrl) {
        syncFromScreen()
        editableUrls.remove(url)
        refreshRows()
    }

    override fun onHomeserverUrlEdited() {
        invalidateOptionsMenu()
    }

    private fun saveUrls() {
        syncFromScreen()
        val edited = editedUrls()
        if (edited.isEmpty() || edited.any { !it.startsWith("http://") && !it.startsWith("https://") }) {
            requireContext().toast(CommonStrings.homeserver_urls_invalid)
        } else {
            viewModel.handle(HomeserverSettingsAction.SetHomeserverUrls(edited))
        }
    }

    private fun editedUrls() = editableUrls.map { it.value.trim().trimEnd('/') }.filter { it.isNotEmpty() }

    // The drag helper reorders models, not our list; resync so a rebuild reproduces what is on screen.
    private fun syncFromScreen() {
        val onScreen = homeserverSettingsController.currentOrderedUrls()
        if (onScreen.isNotEmpty()) {
            editableUrls.clear()
            editableUrls.addAll(onScreen)
        }
    }

    private fun refreshRows() {
        withState(viewModel) { homeserverSettingsController.setData(it) }
        invalidateOptionsMenu()
    }

    private fun enableDragReorder() {
        EpoxyTouchHelper.initDragging(homeserverSettingsController)
                .withRecyclerView(views.genericRecyclerView)
                .forVerticalList()
                .withTarget(HomeserverUrlEditItem_::class.java)
                .andCallbacks(object : EpoxyTouchHelper.DragCallbacks<HomeserverUrlEditItem_>() {
                    override fun onDragStarted(model: HomeserverUrlEditItem_?, itemView: View?, adapterPosition: Int) {
                        itemView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        itemView?.let { ViewCompat.setElevation(it, 6f) }
                    }

                    override fun clearView(model: HomeserverUrlEditItem_?, itemView: View?) {
                        itemView?.let { ViewCompat.setElevation(it, 0f) }
                    }

                    override fun onModelMoved(fromPosition: Int, toPosition: Int, modelBeingMoved: HomeserverUrlEditItem_?, itemView: View?) {
                        syncFromScreen()
                    }

                    override fun onDragReleased(model: HomeserverUrlEditItem_?, itemView: View?) {
                        refreshRows()
                    }
                })
    }

    override fun invalidate() = withState(viewModel) { state ->
        val saved = state.homeserverUrls.map { it.trimEnd('/') }
        if (saved != seededUrls) {
            seededUrls = saved
            editableUrls.clear()
            editableUrls.addAll(saved.map { EditableHomeserverUrl(it) })
            invalidateOptionsMenu()
        }
        homeserverSettingsController.setData(state)
    }

    companion object {
        // Enough of the window covered to be the IME rather than a system bar.
        private const val KEYBOARD_MIN_HEIGHT_DP = 100
    }
}
