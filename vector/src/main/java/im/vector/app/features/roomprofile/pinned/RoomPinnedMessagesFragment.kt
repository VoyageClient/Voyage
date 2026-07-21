/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.pinned

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import com.airbnb.mvrx.args
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.databinding.FragmentRoomPinnedMessagesBinding
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.roomprofile.RoomProfileArgs
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.util.toMatrixItem
import javax.inject.Inject

@AndroidEntryPoint
class RoomPinnedMessagesFragment :
        VectorBaseFragment<FragmentRoomPinnedMessagesBinding>(),
        RoomPinnedMessagesController.Callback {

    @Inject lateinit var controller: RoomPinnedMessagesController
    @Inject lateinit var avatarRenderer: AvatarRenderer
    @Inject lateinit var session: Session

    private val viewModel: RoomPinnedMessagesViewModel by fragmentViewModel()
    private val roomProfileArgs: RoomProfileArgs by args()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentRoomPinnedMessagesBinding {
        return FragmentRoomPinnedMessagesBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        controller.callback = this
        setupToolbar(views.pinnedMessagesToolbar)
                .allowBack()
        renderToolbarRoom()
        views.pinnedMessagesRecyclerView.configureWith(controller, hasFixedSize = true)

        viewModel.observeViewEvents {
            when (it) {
                is RoomPinnedMessagesViewEvents.Failure -> showErrorInSnackbar(it.throwable)
            }
        }
    }

    private fun renderToolbarRoom() {
        val roomSummary = session.getRoom(roomProfileArgs.roomId)?.roomSummary() ?: return
        views.pinnedMessagesToolbarRoomName.text = roomSummary.displayName.prepareForDisplay()
        avatarRenderer.render(roomSummary.toMatrixItem(), views.pinnedMessagesToolbarAvatar)
    }

    override fun onDestroyView() {
        views.pinnedMessagesRecyclerView.cleanup()
        controller.callback = null
        super.onDestroyView()
    }

    override fun invalidate() = withState(viewModel) { state ->
        controller.setData(state)
    }

    override fun onMessageClicked(eventId: String) {
        navigator.openRoom(
                context = requireContext(),
                roomId = roomProfileArgs.roomId,
                eventId = eventId,
        )
    }

    override fun onUnpinClicked(anchor: View, eventId: String) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(0, MENU_UNPIN, 0, getString(CommonStrings.unpin_action))
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_UNPIN -> {
                        viewModel.handle(RoomPinnedMessagesAction.Unpin(eventId))
                        true
                    }
                    else -> false
                }
            }
        }.show()
    }

    companion object {
        private const val MENU_UNPIN = 1
    }
}
