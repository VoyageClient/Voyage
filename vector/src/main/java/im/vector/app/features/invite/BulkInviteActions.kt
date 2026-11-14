/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.invite

import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.features.bulk.BulkActionQueue
import im.vector.app.features.notifications.NotificationDrawerManager
import im.vector.app.features.session.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.room.RoomSummaryQueryParams
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import javax.inject.Inject

/** Accept or reject every pending invite matching a query, one by one on the [BulkActionQueue]. */
class BulkInviteActions @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
        private val bulkActionQueue: BulkActionQueue,
        private val notificationDrawerManager: NotificationDrawerManager,
) {

    fun start(accept: Boolean, queryParams: RoomSummaryQueryParams = ALL_INVITES) {
        val session = activeSessionHolder.getSafeActiveSession() ?: return
        session.coroutineScope.launch {
            val roomIds = withContext(Dispatchers.Default) {
                session.roomService().getRoomSummaries(queryParams).map { it.roomId }
            }
            if (roomIds.isEmpty()) return@launch
            notificationDrawerManager.updateEvents { queue -> roomIds.forEach { queue.clearMemberShipNotificationForRoom(it) } }
            bulkActionQueue.enqueueInvites(roomIds, accept)
        }
    }

    companion object {
        val ALL_INVITES: RoomSummaryQueryParams = roomSummaryQueryParams { memberships = listOf(Membership.INVITE) }
    }
}
