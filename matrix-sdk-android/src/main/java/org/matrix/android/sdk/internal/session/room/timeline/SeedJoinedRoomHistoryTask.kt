/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.session.room.summary.SqlRoomSummaryUpdater
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal interface SeedJoinedRoomHistoryTask : Task<SeedJoinedRoomHistoryTask.Params, Unit> {
    data class Params(
            val roomId: String,
    )
}

/**
 * Pull recent history into a room we have just joined but which sync left with nothing to show: an
 * accepted invite usually arrives carrying only the membership events, so until something is said the
 * room has no preview, no date to sort by, and no content until it is opened.
 */
internal class DefaultSeedJoinedRoomHistoryTask @Inject constructor(
        private val roomAPI: RoomAPI,
        private val paginationTask: PaginationTask,
        private val globalErrorReceiver: GlobalErrorReceiver,
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val sessionDbDispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val roomSummaryUpdater: SqlRoomSummaryUpdater,
) : SeedJoinedRoomHistoryTask {

    override suspend fun execute(params: SeedJoinedRoomHistoryTask.Params) {
        val roomId = params.roomId
        if (stores.roomSummary.get(roomId)?.latestPreviewableEvent != null) return

        val chunk = stores.chunk.lastForward(roomId)
        // Nothing to fetch: this room has no history before what we hold.
        if (chunk?.is_last_backward == 1L) return
        // A sync which was not limited leaves the live chunk without a token to page back from, and a join
        // we only learned of through state leaves no chunk at all. Both ask the server where the live edge
        // is, as an invite preview does.
        val from = chunk?.prev_token ?: liveToken(roomId) ?: return
        val chunkId = chunk?.id ?: database.awaitDbTransaction(sessionDbDispatcher) {
            // Re-read inside the transaction: a sync may have anchored the live chunk since.
            stores.chunk.lastForward(roomId)?.id
                    ?: stores.chunk.insert(roomId, from, null, null, null, isLastForward = true, isLastBackward = false, null, false)
        }

        paginationTask.execute(PaginationTask.Params(roomId, from, PaginationDirection.BACKWARDS, HISTORY_EVENT_COUNT, chunkId))
        // Pagination fills the timeline but leaves the summary alone, and the summary is what the room
        // list reads its preview and its date from.
        database.awaitDbTransaction(sessionDbDispatcher) {
            val joinedAt = stores.roomSummary.get(roomId)?.lastActivityTime
            roomSummaryUpdater.refreshLatestPreviewableEvent(stores, roomId)
            // History older than the join must not drop the room back to the bottom of the list: what the
            // user is looking for it by is having just joined it, not what was last said before that.
            val entity = stores.roomSummary.get(roomId)
            if (entity != null && joinedAt != null && (entity.lastActivityTime ?: 0) < joinedAt) {
                entity.lastActivityTime = joinedAt
                stores.roomSummary.upsert(entity)
            }
        }
    }

    private suspend fun liveToken(roomId: String): String? {
        return executeRequest(globalErrorReceiver) {
            roomAPI.getRoomMessagesFrom(roomId, null, PaginationDirection.BACKWARDS.value, 1, null)
        }.start
    }

    private companion object {
        private const val HISTORY_EVENT_COUNT = 30
    }
}
