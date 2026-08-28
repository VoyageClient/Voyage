/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.spaces.manage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.airbnb.epoxy.EpoxyVisibilityTracker
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.activityViewModel
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.OnBackPressed
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.platform.VectorMenuProvider
import im.vector.app.core.utils.toast
import im.vector.app.databinding.FragmentSpaceAddRoomsBinding
import im.vector.lib.strings.CommonPlurals
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.matrix.android.sdk.api.session.room.model.SpaceChildInfo
import reactivecircus.flowbinding.appcompat.queryTextChanges
import javax.inject.Inject

@AndroidEntryPoint
class SpaceManageRoomsFragment :
        VectorBaseFragment<FragmentSpaceAddRoomsBinding>(),
        OnBackPressed,
        SpaceManageRoomsController.Listener,
        VectorMenuProvider {

    @Inject lateinit var epoxyController: SpaceManageRoomsController

    private val viewModel by fragmentViewModel(SpaceManageRoomsViewModel::class)
    private val sharedViewModel: SpaceManageSharedViewModel by activityViewModel()
    private val epoxyVisibilityTracker = EpoxyVisibilityTracker()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?) = FragmentSpaceAddRoomsBinding.inflate(inflater)

    override fun onBackPressed(toolbarButton: Boolean): Boolean {
        if (withState(viewModel) { it.selectedRooms.isNotEmpty() }) {
            viewModel.handle(SpaceManageRoomViewAction.ClearSelection)
            return true
        }
        parentFragmentManager.popBackStack()
        return true
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar(views.addRoomToSpaceToolbar)
                .setTitle(CommonStrings.space_manage_rooms_and_spaces)
                .allowBack()

        views.createNewRoom.isVisible = false
        epoxyController.listener = this
        views.roomList.configureWith(epoxyController, hasFixedSize = true, dividerDrawable = R.drawable.divider_horizontal)
        epoxyVisibilityTracker.attach(views.roomList)

        views.publicRoomsFilter.queryTextChanges()
                .debounce(200)
                .onEach {
                    viewModel.handle(SpaceManageRoomViewAction.UpdateFilter(it.toString()))
                }
                .launchIn(viewLifecycleOwner.lifecycleScope)

        viewModel.onEach(SpaceManageRoomViewState::actionState) { actionState ->
            when (actionState) {
                is Loading -> {
                    sharedViewModel.handle(SpaceManagedSharedAction.ShowLoading)
                }
                else -> {
                    sharedViewModel.handle(SpaceManagedSharedAction.HideLoading)
                }
            }
        }

        viewModel.observeViewEvents {
            when (it) {
                is SpaceManageRoomViewEvents.BulkActionFailure -> {
                    vectorBaseActivity.toast(errorFormatter.toHumanReadable(it.errorList.firstOrNull()))
                }
            }
        }
    }

    override fun onDestroyView() {
        epoxyController.listener = null
        epoxyVisibilityTracker.detach(views.roomList)
        views.roomList.cleanup()
        super.onDestroyView()
    }

    override fun invalidate() = withState(viewModel) { state ->
        epoxyController.setData(state)

        val selectedCount = state.selectedRooms.size
        if (selectedCount > 0) {
            toolbar?.title = resources.getQuantityString(CommonPlurals.room_details_selected, selectedCount, selectedCount)
            toolbar?.subtitle = null
        } else {
            toolbar?.setTitle(CommonStrings.space_manage_rooms_and_spaces)
            toolbar?.subtitle = state.spaceSummary.invoke()?.displayName
        }
        invalidateOptionsMenu()
        Unit
    }

    override fun toggleSelection(childInfo: SpaceChildInfo) {
        viewModel.handle(SpaceManageRoomViewAction.ToggleSelection(childInfo.childRoomId))
    }

    override fun retry() {
        viewModel.handle(SpaceManageRoomViewAction.RefreshFromServer)
    }

    override fun loadAdditionalItemsIfNeeded() {
        viewModel.handle(SpaceManageRoomViewAction.LoadAdditionalItemsIfNeeded)
    }

    // Selection lives in the toolbar: an ActionMode inflates its bar into the window above the fragment,
    // pushing the content down and fading in over the gap that leaves.
    override fun getMenuRes() = R.menu.menu_manage_space

    override fun handlePrepareMenu(menu: Menu) = withState(viewModel) { state ->
        val hasSelection = state.selectedRooms.isNotEmpty()
        val areAllSuggested = state.childrenInfo.invoke()?.children.orEmpty()
                .filter { state.selectedRooms.contains(it.childRoomId) }
                .all { it.suggested == true }
        menu.findItem(R.id.action_delete)?.isVisible = hasSelection
        menu.findItem(R.id.action_mark_as_suggested)?.isVisible = hasSelection && !areAllSuggested
        menu.findItem(R.id.action_mark_as_not_suggested)?.isVisible = hasSelection && areAllSuggested
    }

    override fun handleMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_delete -> viewModel.handle(SpaceManageRoomViewAction.BulkRemove)
            R.id.action_mark_as_suggested -> viewModel.handle(SpaceManageRoomViewAction.MarkAllAsSuggested(true))
            R.id.action_mark_as_not_suggested -> viewModel.handle(SpaceManageRoomViewAction.MarkAllAsSuggested(false))
            else -> return false
        }
        viewModel.handle(SpaceManageRoomViewAction.ClearSelection)
        return true
    }
}
