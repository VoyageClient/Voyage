/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.events.getFixedRoomMemberContent
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal interface ReanchorRejoinedRoomTask : Task<ReanchorRejoinedRoomTask.Params, Unit> {
    data class Params(
            val roomId: String,
    )
}

/**
 * After a rejoin over a retained kicked/banned timeline, the join sync's window is computed before
 * the join fully registers server-side, so events between the removal and the join are filtered out
 * of it — and the client would treat that token span as fully loaded forever. Re-anchor the fresh
 * live chunk at the join event (/context provides a token just before it) and drop the tainted
 * window rows: back-pagination then re-fetches the whole span under correct post-join visibility.
 */
internal class DefaultReanchorRejoinedRoomTask @Inject constructor(
        private val roomAPI: RoomAPI,
        private val globalErrorReceiver: GlobalErrorReceiver,
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val sessionDbDispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        @UserId private val userId: String,
) : ReanchorRejoinedRoomTask {

    override suspend fun execute(params: ReanchorRejoinedRoomTask.Params) {
        val roomId = params.roomId
        val joinEvent = stores.currentStateEvent.getOne(roomId, EventType.STATE_ROOM_MEMBER, userId)?.root
        val joinEventId = joinEvent?.eventId ?: return
        if (joinEvent.asDomain().getFixedRoomMemberContent()?.membership != Membership.JOIN) return
        val anchor = executeRequest(globalErrorReceiver) {
            roomAPI.getContextOfEvent(roomId, joinEventId, limit = 1)
        }.start ?: return
        database.awaitDbTransaction(sessionDbDispatcher) {
            val live = stores.chunk.lastForward(roomId) ?: return@awaitDbTransaction
            stores.timelineEvent.getInChunkByEventId(live.id, joinEventId) ?: return@awaitDbTransaction
            // Keep the join and the spliced invite; everything older re-fetches on demand.
            val inviteIds = stores.event.getRecentStateOfKey(roomId, EventType.STATE_ROOM_MEMBER, userId, 6)
                    .filter { it.asDomain().getFixedRoomMemberContent()?.membership == Membership.INVITE }
                    .mapNotNull { it.eventId }
                    .toSet()
            stores.timelineEvent.deleteInChunkExcept(live.id, inviteIds + joinEventId)
            stores.chunk.updatePrevToken(live.id, anchor)
        }
    }
}
