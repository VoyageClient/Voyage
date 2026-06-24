/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.knock

import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.core.epoxy.dividerItem
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.app.features.home.AvatarRenderer
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.util.toMatrixItem
import javax.inject.Inject

class RoomKnockRequestsController @Inject constructor(
        private val avatarRenderer: AvatarRenderer,
        private val stringProvider: StringProvider
) : TypedEpoxyController<RoomKnockRequestsViewState>() {

    interface Callback {
        fun onAcceptClicked(roomMember: RoomMemberSummary)
        fun onDeclineClicked(roomMember: RoomMemberSummary)
    }

    var callback: Callback? = null

    override fun buildModels(data: RoomKnockRequestsViewState?) {
        val requests = data?.knockRequests?.invoke() ?: return
        val host = this

        if (requests.isEmpty()) {
            genericFooterItem {
                id("empty")
                text(host.stringProvider.getString(CommonStrings.room_knock_requests_empty).toEpoxyCharSequence())
            }
            return
        }

        requests.forEachIndexed { index, roomMember ->
            val actionInProgress = data.onGoingModerationAction.contains(roomMember.userId)
            roomKnockRequestItem {
                id(roomMember.userId)
                avatarRenderer(host.avatarRenderer)
                matrixItem(roomMember.toMatrixItem())
                reason(data.reasons[roomMember.userId])
                inProgress(actionInProgress)
                acceptListener { host.callback?.onAcceptClicked(roomMember) }
                declineListener { host.callback?.onDeclineClicked(roomMember) }
            }
            if (index < requests.lastIndex) {
                dividerItem {
                    id("divider_${roomMember.userId}")
                }
            }
        }
    }
}
