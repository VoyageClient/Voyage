/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.uploads.files

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.parentFragmentViewModel
import com.airbnb.mvrx.withState
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.epoxy.ListDividerDecoration
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.extensions.iconPopupMenu
import im.vector.app.core.extensions.trackItemsVisibilityChange
import im.vector.app.core.platform.StateView
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.databinding.FragmentGenericStateViewRecyclerBinding
import im.vector.app.features.roomprofile.uploads.RoomUploadsAction
import im.vector.app.features.roomprofile.uploads.RoomUploadsViewModel
import im.vector.app.features.share.forwardEventIntent
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.room.uploads.UploadEvent
import javax.inject.Inject

@AndroidEntryPoint
class RoomUploadsFilesFragment :
        VectorBaseFragment<FragmentGenericStateViewRecyclerBinding>(),
        UploadsFileController.Listener,
        StateView.EventCallback {

    @Inject lateinit var controller: UploadsFileController
    @Inject lateinit var activeSessionHolder: ActiveSessionHolder

    private val uploadsViewModel by parentFragmentViewModel(RoomUploadsViewModel::class)

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentGenericStateViewRecyclerBinding {
        return FragmentGenericStateViewRecyclerBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        views.genericStateViewListStateView.contentView = views.genericStateViewListRecycler
        views.genericStateViewListStateView.eventCallback = this

        views.genericStateViewListRecycler.trackItemsVisibilityChange()
        views.genericStateViewListRecycler.configureWith(controller)
        views.genericStateViewListRecycler.addItemDecoration(ListDividerDecoration(requireContext()))
        controller.listener = this
    }

    override fun onDestroyView() {
        views.genericStateViewListRecycler.cleanup()
        controller.listener = null
        super.onDestroyView()
    }

    override fun onOpenClicked(uploadEvent: UploadEvent) {
        // Same action than Share
        uploadsViewModel.handle(RoomUploadsAction.Share(uploadEvent))
    }

    override fun onRetryClicked() {
        uploadsViewModel.handle(RoomUploadsAction.Retry)
    }

    override fun loadMore() {
        uploadsViewModel.handle(RoomUploadsAction.LoadMore)
    }

    override fun onDownloadClicked(uploadEvent: UploadEvent) {
        uploadsViewModel.handle(RoomUploadsAction.Download(uploadEvent))
    }

    override fun onMoreClicked(uploadEvent: UploadEvent, anchor: View) {
        val popup = iconPopupMenu(requireContext(), anchor, R.menu.menu_uploads_file_actions)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.uploadsFileShare -> uploadsViewModel.handle(RoomUploadsAction.Share(uploadEvent))
                R.id.uploadsFileForward -> forward(uploadEvent)
                R.id.uploadsFileShowInChat -> withState(uploadsViewModel) { navigator.openRoom(requireActivity(), it.roomId, uploadEvent.eventId) }
            }
            true
        }
        popup.show()
    }

    private fun forward(uploadEvent: UploadEvent) = withState(uploadsViewModel) { state ->
        val event = uploadEvent.root.let { it.copy(roomId = it.roomId ?: state.roomId) }
        startActivity(forwardEventIntent(requireContext(), activeSessionHolder.getSafeActiveSession(), event))
    }

    override fun invalidate() = withState(uploadsViewModel) { state ->
        if (state.fileEvents.isEmpty()) {
            when (state.asyncEventsRequest) {
                is Loading -> {
                    views.genericStateViewListStateView.state = StateView.State.Loading
                }
                is Fail -> {
                    views.genericStateViewListStateView.state = StateView.State.Error(errorFormatter.toHumanReadable(state.asyncEventsRequest.error))
                }
                is Success -> {
                    if (state.hasMore) {
                        // We need to load more items
                        loadMore()
                    } else {
                        views.genericStateViewListStateView.state = StateView.State.Empty(
                                title = getString(CommonStrings.uploads_files_no_result),
                                image = ContextCompat.getDrawable(requireContext(), R.drawable.ic_file)
                        )
                    }
                }
                else -> Unit
            }
        } else {
            views.genericStateViewListStateView.state = StateView.State.Content
            views.genericStateViewListRecycler.post { loadMoreIfSpinnerShown() }
            controller.setData(state)
        }
    }

    override fun onResume() {
        super.onResume()
        loadMoreIfSpinnerShown()
    }

    // The spinner row only reports becoming visible, so a page that leaves it on screen is followed up here.
    // Resumed only: the pager keeps the other tab's list attached, spinner and all.
    private fun loadMoreIfSpinnerShown() = withState(uploadsViewModel) { state ->
        if (isResumed && controller.isLoadMoreVisible && state.hasMore && state.asyncEventsRequest is Success) loadMore()
    }
}
