/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.relation

import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.relation.MassRedactionRange
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.filter.RoomEventFilter
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.session.room.timeline.PaginationDirection
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

// One page of a room's history, keeping only [senderId]'s event ids. Drives mass redaction of history
// that isn't in the local DB. With an [anchorToken] the walk runs FORWARDS from the user's join to the
// live edge (never touching pre-join history); otherwise it runs backwards from the live edge. Resumable:
// [from] null starts at the walk's origin, and [Result.nextToken] feeds the next call until null.
internal interface FetchUserEventsTask : Task<FetchUserEventsTask.Params, FetchUserEventsTask.Result> {
    data class Params(
            val roomId: String,
            val senderId: String,
            val from: String?,
            val floorTs: Long?,
            /** Pagination token at the user's earliest event; when set, the walk pages forwards from it. */
            val anchorToken: String?,
            val range: MassRedactionRange = MassRedactionRange.ALL,
    )

    data class Result(
            val eventIds: List<String>,
            val nextToken: String?,
            val redactionTargets: List<String> = emptyList(),
            /** Events the server served as already redacted, for reconciling stale local copies. */
            val alreadyRedactedIds: List<String> = emptyList(),
    )
}

internal class DefaultFetchUserEventsTask @Inject constructor(
        private val roomAPI: RoomAPI,
        private val globalErrorReceiver: GlobalErrorReceiver,
        private val stores: SessionStores,
) : FetchUserEventsTask {

    override suspend fun execute(params: FetchUserEventsTask.Params): FetchUserEventsTask.Result {
        val forwards = params.anchorToken != null
        val fromToken = params.from
                ?: (if (forwards) params.anchorToken else stores.chunk.lastForward(params.roomId)?.prev_token)
                ?: return FetchUserEventsTask.Result(emptyList(), null)
        val direction = if (forwards) PaginationDirection.FORWARDS else PaginationDirection.BACKWARDS

        // Server-side sender filter so pages carry only this user's events.
        val filter = RoomEventFilter(senders = listOf(params.senderId)).toJSONString()
        val response = executeRequest(globalErrorReceiver, canRetry = true) {
            roomAPI.getRoomMessagesFromForMassRedaction(params.roomId, fromToken, direction.value, PAGE_SIZE, filter)
        }
        val chunk = response.chunk.orEmpty()
        val bySender = chunk.filter { it.senderId == params.senderId }
        val eventIds = bySender
                // Skip redaction events themselves (redacting a redaction is pointless and leaves a
                // redacted-redaction that breaks event grouping), already-redacted events, and events with
                // no content (already pruned server-side — nothing left to redact even if unsigned doesn't
                // say why). Mirrors the local enumeration in EventSqlStore.getRedactableEventIdsBySender.
                .filter { it.type != EventType.REDACTION && !it.isRedacted() && !it.content.isNullOrEmpty() }
                .filter { params.range.contains(it.originServerTs) }
                .mapNotNull { it.eventId }
        // Redaction events in this page and what they target: server state can disagree with itself (a
        // redaction can exist in history while its target is still served unpruned), so surface targets to
        // the caller for cross-referencing instead of trusting the served event's unsigned data alone.
        val redactionTargets = chunk
                .filter { it.type == EventType.REDACTION }
                .mapNotNull { it.redacts ?: it.content?.get("redacts") as? String }
        val alreadyRedactedIds = bySender
                .filter { it.type != EventType.REDACTION && it.isRedacted() }
                .mapNotNull { it.eventId }
        // Backwards mode: stop once we've reached the user's earliest event (their first membership sits
        // at floorTs, and it's in this sender-filtered chunk) — no point paging older history where they
        // have nothing. Forward mode ends at the live edge on its own. Otherwise a filtered page can be
        // empty yet still have more behind it, so only stop when the token stops advancing.
        val floor = params.floorTs
        val reachedFloor = !forwards && floor != null &&
                chunk.any { event -> event.originServerTs?.let { it <= floor } == true }
        // Symmetrically, a forward walk that has passed the range's upper bound has nothing left to find.
        val passedCeiling = forwards && params.range.toTs?.let { ceiling ->
            chunk.any { event -> event.originServerTs?.let { it > ceiling } == true }
        } == true
        val nextToken = if (reachedFloor || passedCeiling) null else response.end?.takeIf { it != fromToken }
        return FetchUserEventsTask.Result(eventIds, nextToken, redactionTargets, alreadyRedactedIds)
    }

    companion object {
        // Synapse caps /messages at 1000; bigger pages make the scan over already-redacted history
        // 10x fewer round trips than the old 100.
        private const val PAGE_SIZE = 1000
    }
}
