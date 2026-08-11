/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.filter.FilterRepository
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.task.Task
import timber.log.Timber
import javax.inject.Inject

/**
 * Resolves the earliest event of a room's visible history, so "jump to start" doesn't have to
 * back-paginate the whole room. Two routes:
 *
 * - MSC3567: /messages with no `from` and `dir=f` starts at the first event the user may see. The page
 *   is persisted, so the returned event is already in a chunk.
 * - MSC3030: /timestamp_to_event at ts=0 forwards names the first event, which is then resolved to a
 *   chunk with /context.
 *
 * Either way the returned event is in a chunk by the time this completes. Null means the server would
 * serve neither route — its history simply isn't reachable except by paginating back to it.
 */
internal interface FetchRoomStartTask : Task<FetchRoomStartTask.Params, String?> {

    data class Params(
            val roomId: String,
            // The room's create event, which any true start page must begin with. Servers that don't
            // implement MSC3567 answer a from-less request from wherever their own ordering starts —
            // Synapse returns a stream-ordered page, months off and not even chronological — and
            // persisting that would splice unrelated history into the middle of the timeline.
            val expectedFirstEventId: String?,
            val limit: Int = 30,
    )
}

internal class DefaultFetchRoomStartTask @Inject constructor(
        private val roomAPI: RoomAPI,
        private val filterRepository: FilterRepository,
        private val tokenChunkEventPersistor: TokenChunkEventPersistor,
        private val contextOfEventTask: GetContextOfEventTask,
        private val globalErrorReceiver: GlobalErrorReceiver,
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
) : FetchRoomStartTask {

    override suspend fun execute(params: FetchRoomStartTask.Params): String? {
        return fetchStartPage(params) ?: resolveFirstEventByTimestamp(params)
    }

    private suspend fun fetchStartPage(params: FetchRoomStartTask.Params): String? {
        val filter = filterRepository.getRoomFilterBody()
        val chunk = tryOrNull("FetchRoomStart ${params.roomId}: from-less /messages rejected") {
            executeRequest(globalErrorReceiver, canRetry = true) {
                roomAPI.getRoomMessagesFrom(params.roomId, null, PaginationDirection.FORWARDS.value, params.limit, filter)
            }
        } ?: return null
        val firstEventId = chunk.events.firstOrNull { it.eventId != null }?.eventId ?: return null
        if (params.expectedFirstEventId != null && firstEventId != params.expectedFirstEventId) {
            Timber.w("FetchRoomStart ${params.roomId}: from-less page starts at $firstEventId, not the create event; discarding")
            return null
        }
        tokenChunkEventPersistor.insertInDb(chunk, params.roomId, PaginationDirection.FORWARDS)
        return firstEventId
    }

    // Nothing precedes ts=0, so the closest event forwards from it is the room's first. Those first few
    // events are often individually unreachable — a room-v12 create event can't be fetched by its id, and
    // a server that only holds the room from a later point answers /context with 403 or 404 — so step
    // past each refusal to the next event instead of abandoning the jump.
    private suspend fun resolveFirstEventByTimestamp(params: FetchRoomStartTask.Params): String? {
        var ts = 0L
        repeat(MAX_START_PROBES) {
            val response = probeTimestamp(params.roomId, ts) ?: return null
            if (response.eventId != params.expectedFirstEventId && resolveContext(params.roomId, response.eventId)) {
                return response.eventId
            }
            ts = response.originServerTs + 1
        }
        return null
    }

    private suspend fun resolveContext(roomId: String, eventId: String): Boolean {
        tryOrNull("FetchRoomStart $roomId: context of $eventId failed") {
            contextOfEventTask.execute(GetContextOfEventTask.Params(roomId, eventId))
        } ?: return false
        return database.awaitDbTransaction(dispatcher) { stores.chunk.findChunkIdIncludingEvent(roomId, eventId) } != null
    }

    private suspend fun probeTimestamp(roomId: String, ts: Long): TimestampToEventResponse? =
            tryOrNull("FetchRoomStart $roomId: timestamp probe at $ts failed") {
                executeRequest(globalErrorReceiver, canRetry = true) {
                    roomAPI.getEventForTimestamp(roomId, ts, PaginationDirection.FORWARDS.value)
                }
            }

    companion object {
        private const val MAX_START_PROBES = 3
    }
}
