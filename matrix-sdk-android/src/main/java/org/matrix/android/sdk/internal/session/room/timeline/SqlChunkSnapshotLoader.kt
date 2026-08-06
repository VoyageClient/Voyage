/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.util.MatrixPerf
import org.matrix.android.sdk.internal.database.mapper.TimelineEventMapper
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores

/**
 * The core building block for the SQLDelight timeline rebuild (replacing Realm's incremental
 * OrderedCollectionChangeSet model): a chunk's timeline events as an ordered snapshot, and as a Flow
 * that re-emits the whole rebuilt snapshot whenever the chunk's rows change. The owning timeline maps
 * via [TimelineEventMapper] and posts the snapshot through its existing post-snapshot signal.
 */
internal class SqlChunkSnapshotLoader(
        private val database: SessionSqlDatabase,
        private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val timelineEventMapper: TimelineEventMapper,
) {

    /** One-shot snapshot of a chunk's timeline events, most-recent first (display_index DESC). */
    fun chunkSnapshot(chunkId: Long): List<TimelineEvent> {
        val perfStart = MatrixPerf.now()
        val entities = stores.timelineEvent.getByChunk(chunkId)
        MatrixPerf.end(perfStart) { "timeline.chunkSnapshot.load chunk=$chunkId rows=${entities.size}" }
        val mapStart = MatrixPerf.now()
        return entities.map { timelineEventMapper.map(it) }
                .also { MatrixPerf.end(mapStart) { "timeline.chunkSnapshot.map chunk=$chunkId rows=${it.size}" } }
    }

    /** Change signal for a chunk's rows. Deliberately does NOT execute the query — the collector
     *  rebuilds via [chunkSnapshot] itself, and computing a mapped list here just to discard it doubled
     *  the per-change mapping cost. */
    fun chunkChangesFlow(chunkId: Long): Flow<Unit> =
            database.timelineEventQueries.selectByChunk(chunkId)
                    .asFlow()
                    .map { }

    /** Paginated window of a chunk by display-index range (for loadMore). */
    fun eventsInRange(chunkId: Long, from: Long, to: Long): List<TimelineEvent> =
            stores.timelineEvent.getByChunkRange(chunkId, from, to).map { timelineEventMapper.map(it) }

    /** The chunk's events strictly newer than [afterDisplayIndex], most-recent first. */
    fun chunkSnapshotAfter(chunkId: Long, afterDisplayIndex: Long): List<TimelineEvent> =
            stores.timelineEvent.getByChunkAfterIndex(chunkId, afterDisplayIndex).map { timelineEventMapper.map(it) }

    /** The [limit] newest rows of a chunk, most-recent first — used to bound the live-chunk mapping. */
    fun chunkSnapshotNewest(chunkId: Long, limit: Long): List<TimelineEvent> {
        val perfStart = MatrixPerf.now()
        val entities = stores.timelineEvent.getByChunkNewest(chunkId, limit)
        MatrixPerf.end(perfStart) { "timeline.chunkSnapshotNewest.load chunk=$chunkId rows=${entities.size}" }
        return entities.map { timelineEventMapper.map(it) }
    }

    /** The [limit] rows just older than [beforeDisplayIndex], most-recent first — appends to a bounded slice
     *  as the window grows, so widening it costs O(step) instead of re-mapping the whole slice. */
    fun chunkSnapshotOlderThan(chunkId: Long, beforeDisplayIndex: Long, limit: Long): List<TimelineEvent> =
            stores.timelineEvent.getByChunkBeforeIndex(chunkId, beforeDisplayIndex, limit).map { timelineEventMapper.map(it) }

    fun chunkEventCount(chunkId: Long): Long = stores.timelineEvent.countByChunk(chunkId)

    /** The room's sending (local-echo) events — timeline_event rows with chunk_id NULL — newest first. */
    fun sendingEvents(roomId: String): List<TimelineEvent> =
            stores.timelineEvent.getSendingByRoom(roomId).map { timelineEventMapper.map(it) }.asReversed()

    /** Change signal for the room's sending events (same no-execute rationale as [chunkChangesFlow]). */
    fun sendingChangesFlow(roomId: String): Flow<Unit> =
            database.timelineEventQueries.selectSendingByRoom(roomId)
                    .asFlow()
                    .map { }

    /** The ignored-user id set, re-emitting whenever the user (un)ignores someone — so the timeline can
     *  re-filter instantly without a re-sync. */
    fun ignoredUserIdsFlow(): Flow<List<String>> =
            database.ignoredUserQueries.selectAll()
                    .asFlow()
                    .mapToList(dispatcher)

    /** Emits when any event's annotation summary (reactions/edits/etc) changes — the chunk flow only watches
     *  timeline_event rows, so it misses these. Pure signal (no query execution). */
    fun annotationSummaryChangesFlow(roomId: String): Flow<Unit> =
            database.eventAnnotationsSummaryQueries.selectSummariesForRoom(roomId)
                    .asFlow()
                    .map { }

    /**
     * Emits when anyone's read receipt moves in this room. A sync carrying only an m.receipt writes
     * neither timeline_event nor the annotation summaries, so nothing else notices.
     *
     * Unlike the signal-only flows above, this one runs its query so the consumer can tell a change in
     * THIS room from the table-level notifications SQLDelight fires for every joined room.
     */
    fun readReceiptChangesFlow(roomId: String): Flow<List<String>> =
            database.readReceiptQueries.selectReceiptStateInRoom(roomId)
                    .asFlow()
                    .mapToList(dispatcher)
                    .map { rows -> rows.map { "${it.event_id}|${it.user_id}|${it.origin_server_ts}" } }
}
