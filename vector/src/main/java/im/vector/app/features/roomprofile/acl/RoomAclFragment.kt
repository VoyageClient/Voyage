/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

@file:Suppress("DEPRECATION")

package im.vector.app.features.roomprofile.acl

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.SimpleItemAnimator
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.OnBackPressed
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.utils.toast
import im.vector.app.databinding.FragmentRoomSettingGenericBinding
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.util.toDisplayMatrixItem
import javax.inject.Inject

@AndroidEntryPoint
class RoomAclFragment : VectorBaseFragment<FragmentRoomSettingGenericBinding>(), RoomAclController.Callback, OnBackPressed {
    @Inject lateinit var controller: RoomAclController
    @Inject lateinit var avatarRenderer: AvatarRenderer
    private val viewModel: RoomAclViewModel by fragmentViewModel()
    private var draggedEntries: List<RoomAclEntry>? = null
    private var loading = false
    private val showLoading = Runnable {
        if (loading && isAdded) views.waitingView.root.isVisible = true
    }
    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?) =
            FragmentRoomSettingGenericBinding.inflate(inflater, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHasOptionsMenu(true)
        controller.callback = this
        setupToolbar(views.roomSettingsToolbar).allowBack()
        views.roomSettingsRecyclerView.configureWith(controller, hasFixedSize = false)
        (views.roomSettingsRecyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        views.roomSettingsRecyclerView.addItemDecoration(im.vector.app.core.epoxy.ListDividerDecoration(requireContext(), drawUnder = { model -> model is RoomAclEntryItem_ }))
        com.airbnb.epoxy.EpoxyTouchHelper.initDragging(controller).withRecyclerView(views.roomSettingsRecyclerView).forVerticalList()
                .withTarget(RoomAclEntryItem_::class.java)
                .andCallbacks(object : com.airbnb.epoxy.EpoxyTouchHelper.DragCallbacks<RoomAclEntryItem_>() {
                    override fun onDragStarted(model: RoomAclEntryItem_?, itemView: View?, adapterPosition: Int) {
                        itemView?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    }
                    override fun clearView(model: RoomAclEntryItem_?, itemView: View?) = Unit
                    override fun onModelMoved(fromPosition: Int, toPosition: Int, modelBeingMoved: RoomAclEntryItem_?, itemView: View?) {
                        val moved = modelBeingMoved ?: return
                        val targetAllowed = controller.dropTargetAllowed(fromPosition, toPosition, moved.entry().id) ?: return
                        val current = controller.currentOrderedEntries()
                        draggedEntries = current.map { entry ->
                            if (entry.id == moved.entry().id) entry.copy(allowed = targetAllowed) else entry
                        }
                        controller.setDraggedEntries(draggedEntries.orEmpty())
                    }
                    override fun onDragReleased(model: RoomAclEntryItem_?, itemView: View?) {
                        val entries = draggedEntries ?: return
                        draggedEntries = null
                        viewModel.handle(RoomAclAction.ReplaceOrder(entries))
                    }
                })
        viewModel.observeViewEvents {
            when (it) {
                is RoomAclEvent.Failure -> showFailure(it.throwable)
                RoomAclEvent.DirtyChanged -> invalidateApplyAction()
                RoomAclEvent.Saved -> {
                    requireActivity().invalidateOptionsMenu()
                    activity?.toast(CommonStrings.room_settings_save_success)
                }
            }
        }
    }
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.room_acl, menu)
        (menu.findItem(R.id.roomAclSearchAction)?.actionView as? SearchView)?.apply {
            queryHint = getString(CommonStrings.room_acl_search_hint)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?) = false
                override fun onQueryTextChange(query: String?) = true.also { viewModel.handle(RoomAclAction.Search(query.orEmpty())) }
            })
        }
    }
    override fun onPrepareOptionsMenu(menu: Menu) {
        withState(viewModel) { state ->
            val enabledTint = ThemeUtils.getColor(requireContext(), im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
            val disabledTint = ThemeUtils.getColor(requireContext(), im.vector.lib.ui.styles.R.attr.vctr_content_quaternary)
            menu.findItem(R.id.roomAclApplyAction)?.apply {
                isEnabled = state.canEdit && viewModel.hasUnsavedChanges() && !state.saving
                icon?.mutate()?.let { DrawableCompat.setTint(it, if (isEnabled) enabledTint else disabledTint) }
            }
        }
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean = if (item.itemId == R.id.roomAclApplyAction) {
        viewModel.handle(RoomAclAction.Save)
        true
    } else {
        super.onOptionsItemSelected(item)
    }
    private var applyEnabled: Boolean? = null
    override fun invalidate() = withState(viewModel) { state ->
        controller.setData(state)
        state.roomSummary()?.let { summary ->
            views.roomSettingsToolbarTitleView.text = summary.displayName.prepareForDisplay()
            avatarRenderer.render(summary.toDisplayMatrixItem(), views.roomSettingsToolbarAvatarImageView)
            views.roomSettingsDecorationToolbarAvatarImageView.render(summary.roomEncryptionTrustLevel)
        }
        val enabled = state.canEdit && viewModel.hasUnsavedChanges() && !state.saving
        if (applyEnabled != enabled) {
            applyEnabled = enabled
            requireActivity().invalidateOptionsMenu()
        }
        if (state.saving) {
            views.roomSettingsRecyclerView.removeCallbacks(showLoading)
            views.waitingView.root.isVisible = true
        } else if (state.loading) {
            if (!loading) views.roomSettingsRecyclerView.postDelayed(showLoading, 150)
            loading = true
        } else {
            loading = false
            views.roomSettingsRecyclerView.removeCallbacks(showLoading)
            views.waitingView.root.isVisible = false
        }
    }
    override fun onDestroyView() {
        views.roomSettingsRecyclerView.removeCallbacks(showLoading)
        controller.callback = null
        views.roomSettingsRecyclerView.cleanup()
        super.onDestroyView()
    }
    private fun invalidateApplyAction() = requireActivity().invalidateOptionsMenu()

    override fun onAdd(allowed: Boolean) = viewModel.handle(RoomAclAction.Add(allowed))

    override fun onUpdate(entry: RoomAclEntry, server: String, allowed: Boolean) =
            viewModel.handle(RoomAclAction.Update(entry.id, server, allowed))

    override fun onTypeChanged(entry: RoomAclEntry) = viewModel.handle(RoomAclAction.ToggleType(entry.id))

    override fun onRemove(entry: RoomAclEntry) = viewModel.handle(RoomAclAction.Remove(entry.id))

    override fun onIpLiteralsChanged(value: Boolean) = viewModel.handle(RoomAclAction.SetIpLiterals(value))

    override fun onExpandedToggle(allowed: Boolean) = viewModel.handle(RoomAclAction.ToggleExpanded(allowed))

    override fun onBackPressed(toolbarButton: Boolean): Boolean {
        if (!viewModel.hasUnsavedChanges()) return false
        MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.room_acl_unsaved_title)
                .setMessage(CommonStrings.room_acl_unsaved_message)
                .apply {
                    withState(viewModel) { state ->
                        if (state.canEdit && !state.saving) setPositiveButton(CommonStrings.image_pack_apply) { _, _ -> viewModel.handle(RoomAclAction.Save) }
                    }
                }
                .setNegativeButton(CommonStrings.room_acl_unsaved_discard) { _, _ -> parentFragmentManager.popBackStack() }
                .setNeutralButton(CommonStrings.action_cancel, null)
                .show()
        return true
    }
}
