/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomdirectory.roompreview

import android.content.Context
import android.content.Intent
import android.os.Parcelable
import android.view.View
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.addFragment
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.databinding.ActivitySimpleBinding
import im.vector.app.features.roomdirectory.RoomDirectoryData
import im.vector.lib.core.utils.compat.getParcelableExtraCompat
import kotlinx.parcelize.Parcelize
import org.matrix.android.sdk.api.session.permalinks.PermalinkData
import org.matrix.android.sdk.api.session.room.model.RoomType
import org.matrix.android.sdk.api.session.room.model.roomdirectory.PublicRoom
import org.matrix.android.sdk.api.util.MatrixItem

@Parcelize
data class RoomPreviewData(
        val roomId: String,
        val eventId: String? = null,
        val roomName: String? = null,
        val roomAlias: String? = null,
        val roomType: String? = null,
        val topic: String? = null,
        val numJoinedMembers: Int? = null,
        val worldReadable: Boolean = false,
        val avatarUrl: String? = null,
        val homeServers: List<String> = emptyList(),
        val peekFromServer: Boolean = false,
        val buildTask: Boolean = false,
        /** Set when the live timeline preview already failed for this room, so the no-preview screen must not bounce back to it. */
        val noTimelinePreview: Boolean = false,
        val fromEmailInvite: PermalinkData.RoomEmailInviteLink? = null
) : Parcelable {
    val matrixItem: MatrixItem
        get() = MatrixItem.RoomItem(roomId, roomName ?: roomAlias, avatarUrl)
}

@AndroidEntryPoint
class RoomPreviewActivity : VectorBaseActivity<ActivitySimpleBinding>() {

    companion object {
        private const val ARG = "ARG"

        fun newIntent(context: Context, roomPreviewData: RoomPreviewData): Intent {
            return Intent(context, RoomPreviewActivity::class.java).apply {
                putExtra(ARG, roomPreviewData)
            }
        }

        fun newIntent(context: Context, publicRoom: PublicRoom, roomDirectoryData: RoomDirectoryData): Intent {
            val roomPreviewData = RoomPreviewData(
                    roomId = publicRoom.roomId,
                    roomName = publicRoom.name,
                    roomAlias = publicRoom.getPrimaryAlias(),
                    topic = publicRoom.topic,
                    numJoinedMembers = publicRoom.numJoinedMembers,
                    worldReadable = publicRoom.worldReadable,
                    avatarUrl = publicRoom.avatarUrl,
                    homeServers = listOfNotNull(roomDirectoryData.homeServer)
            )
            return newIntent(context, roomPreviewData)
        }
    }

    override fun getBinding() = ActivitySimpleBinding.inflate(layoutInflater)

    override fun getCoordinatorLayout() = views.coordinatorLayout

    override val rootView: View
        get() = views.simpleFragmentContainer

    override fun initUiAndData() {
        if (isFirstCreation()) {
            val args = intent.getParcelableExtraCompat<RoomPreviewData>(ARG)

            val session = activeSessionHolder.getSafeActiveSession()
            // A room with a real local row (left/banned) or a space must go through the classic
            // screen: the timeline preview can't render either correctly. A retained previous peek
            // is fine — the getter resolves it back to the peeked room.
            val hasRealLocalRoom = session != null && args != null &&
                    session.roomService().getRoom(args.roomId) != null &&
                    !session.roomService().isRoomPeeked(args.roomId)
            val canTimelinePreview = args?.worldReadable == true && !args.noTimelinePreview &&
                    args.roomType != RoomType.SPACE &&
                    !hasRealLocalRoom
            if (args != null && canTimelinePreview && session != null) {
                // Preview through the normal room screen, backed by an SDK-level peeked room.
                session.roomService().registerRoomPeek(
                        roomId = args.roomId,
                        viaServers = args.homeServers,
                        roomName = args.roomName,
                        roomAvatarUrl = args.avatarUrl,
                        roomTopic = args.topic,
                        roomAlias = args.roomAlias,
                )
                navigator.openRoom(this, args.roomId, args.eventId, args.buildTask)
                finish()
            } else if (args != null) {
                addFragment(views.simpleFragmentContainer, RoomPreviewNoPreviewFragment::class.java, args)
            }
        }
    }
}
