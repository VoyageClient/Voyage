/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.knock

import android.text.format.DateUtils
import com.airbnb.epoxy.TypedEpoxyController
import im.vector.app.core.epoxy.dividerItem
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.ui.list.genericFooterItem
import im.vector.app.features.home.AvatarRenderer
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.core.utils.timer.Clock
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.util.toMatrixItem
import javax.inject.Inject

class RoomKnockRequestsController @Inject constructor(
        private val avatarRenderer: AvatarRenderer,
        private val stringProvider: StringProvider,
        private val clock: Clock,
) : TypedEpoxyController<RoomKnockRequestsViewState>() {

    interface Callback {
        fun onRequestClicked(roomMember: RoomMemberSummary)
        fun onAcceptClicked(roomMember: RoomMemberSummary)
        fun onDeclineClicked(roomMember: RoomMemberSummary)
    }

    var callback: Callback? = null

    private val expandedReasons = mutableSetOf<String>()

    override fun buildModels(data: RoomKnockRequestsViewState?) {
        val requests = data?.knockRequests?.invoke()?.filterNot { it.userId in data.handledRequests } ?: return
        val host = this

        if (requests.isEmpty()) {
            genericFooterItem {
                id("empty")
                text(host.stringProvider.getString(CommonStrings.room_knock_requests_empty).toEpoxyCharSequence())
            }
            return
        }

        val now = clock.epochMillis()
        requests.forEachIndexed { index, roomMember ->
            val userId = roomMember.userId
            roomKnockRequestItem {
                id(userId)
                avatarRenderer(host.avatarRenderer)
                matrixItem(host.avatarRenderer.displayedItem(roomMember.toMatrixItem(), data.roomId))
                reason(data.reasons[userId])
                reasonExpanded(userId in host.expandedReasons)
                requestTime(data.requestTimestamps[userId]?.let { host.formatRequestTime(it, now) })
                canAccept(data.canAccept)
                canDecline(data.canDecline)
                inProgress(userId in data.onGoingModerationAction)
                clickListener { host.callback?.onRequestClicked(roomMember) }
                reasonClickListener { host.toggleReason(userId, data) }
                acceptListener { host.callback?.onAcceptClicked(roomMember) }
                declineListener { host.callback?.onDeclineClicked(roomMember) }
            }
            if (index < requests.lastIndex) {
                dividerItem {
                    id("divider_$userId")
                }
            }
        }
    }

    private fun toggleReason(userId: String, data: RoomKnockRequestsViewState) {
        if (!expandedReasons.remove(userId)) expandedReasons.add(userId)
        setData(data)
    }

    private fun formatRequestTime(ts: Long, now: Long): String {
        return DateUtils.getRelativeTimeSpanString(ts.coerceAtMost(now), now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }
}
