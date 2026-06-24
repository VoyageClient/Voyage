/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomdirectory.pendingrequests

import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.mvrx.Success
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.list.spaceChildInfoItem
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.util.toMatrixItem
import javax.inject.Inject

class PendingJoinRequestsController @Inject constructor(
        private val avatarRenderer: AvatarRenderer,
        private val stringProvider: StringProvider
) : TypedEpoxyController<PendingJoinRequestsViewState>() {

    interface Listener {
        fun onCancelRequest(roomSummary: RoomSummary)
    }

    var listener: Listener? = null

    override fun buildModels(data: PendingJoinRequestsViewState?) {
        data ?: return
        val host = this
        val requests = (data.requests as? Success)?.invoke().orEmpty()
                .filterNot { data.cancelledRoomIds.contains(it.roomId) }

        if (requests.isEmpty()) {
            genericFooterItem {
                id("empty")
                text(host.stringProvider.getString(CommonStrings.pending_join_requests_empty).toEpoxyCharSequence())
            }
            return
        }

        requests.forEach { summary ->
            val inProgress = data.onGoingCancellation.contains(summary.roomId)
            spaceChildInfoItem {
                id(summary.roomId)
                matrixItem(summary.toMatrixItem())
                avatarRenderer(host.avatarRenderer)
                topic(summary.topic)
                // The room's joined-member count includes us; show the count of other members.
                memberCount((summary.joinedMembersCount ?: 0).minus(1).coerceAtLeast(0))
                loading(inProgress)
                destructiveButton(true)
                buttonLabel(host.stringProvider.getString(CommonStrings.action_cancel))
                buttonClickListener { host.listener?.onCancelRequest(summary) }
            }
        }
    }
}
