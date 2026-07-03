/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.relation

import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.filter.RoomEventFilter
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.session.room.timeline.PaginationDirection
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

// One backwards page of a room's history, keeping only [senderId]'s event ids. Drives mass redaction of
// history that isn't in the local DB. Resumable: [from] null starts at the live edge, and [Result.nextToken]
// feeds the next call until it comes back null (start of the room reached).
internal interface FetchUserEventsTask : Task<FetchUserEventsTask.Params, FetchUserEventsTask.Result> {
    data class Params(
            val roomId: String,
            val senderId: String,
            val from: String?,
            val floorTs: Long?
    )

    data class Result(
            val eventIds: List<String>,
            val nextToken: String?
    )
}

internal class DefaultFetchUserEventsTask @Inject constructor(
        private val roomAPI: RoomAPI,
        private val globalErrorReceiver: GlobalErrorReceiver,
        private val stores: SessionStores,
) : FetchUserEventsTask {

    override suspend fun execute(params: FetchUserEventsTask.Params): FetchUserEventsTask.Result {
        val fromToken = params.from
                ?: stores.chunk.lastForward(params.roomId)?.prev_token
                ?: return FetchUserEventsTask.Result(emptyList(), null)

        // Server-side sender filter so pages carry only this user's events.
        val filter = RoomEventFilter(senders = listOf(params.senderId)).toJSONString()
        val response = executeRequest(globalErrorReceiver, canRetry = true) {
            roomAPI.getRoomMessagesFrom(params.roomId, fromToken, PaginationDirection.BACKWARDS.value, PAGE_SIZE, filter)
        }
        val eventIds = response.chunk.orEmpty()
                // Skip redaction events themselves (redacting a redaction is pointless and leaves a
                // redacted-redaction that breaks event grouping) and already-redacted events. Mirrors the
                // local enumeration in EventSqlStore.getRedactableEventIdsBySender.
                .filter { it.senderId == params.senderId && it.type != EventType.REDACTION && !it.isRedacted() }
                .mapNotNull { it.eventId }
        // Stop once we've reached the user's earliest event (their first membership sits at floorTs, and it's
        // in this sender-filtered chunk) — no point paging older history where they have nothing. Otherwise a
        // filtered page can be empty yet still have more behind it, so only stop when the token stops advancing.
        val floor = params.floorTs
        val reachedFloor = floor != null &&
                response.chunk.orEmpty().any { event -> event.originServerTs?.let { it <= floor } == true }
        val nextToken = if (reachedFloor) null else response.end?.takeIf { it != fromToken }
        return FetchUserEventsTask.Result(eventIds, nextToken)
    }

    companion object {
        private const val PAGE_SIZE = 100
    }
}
