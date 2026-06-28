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
    fun chunkSnapshot(chunkId: Long): List<TimelineEvent> =
            stores.timelineEvent.getByChunk(chunkId).map { timelineEventMapper.map(it) }

    /** Auto-rebuilding snapshot: re-emits the full mapped list whenever the chunk's rows change. */
    fun chunkSnapshotFlow(chunkId: Long): Flow<List<TimelineEvent>> =
            database.timelineEventQueries.selectByChunk(chunkId)
                    .asFlow()
                    .mapToList(dispatcher)
                    .map { chunkSnapshot(chunkId) }

    /** Paginated window of a chunk by display-index range (for loadMore). */
    fun eventsInRange(chunkId: Long, from: Long, to: Long): List<TimelineEvent> =
            stores.timelineEvent.getByChunkRange(chunkId, from, to).map { timelineEventMapper.map(it) }

    /** The room's sending (local-echo) events — timeline_event rows with chunk_id NULL — newest first. */
    fun sendingEvents(roomId: String): List<TimelineEvent> =
            stores.timelineEvent.getSendingByRoom(roomId).map { timelineEventMapper.map(it) }.asReversed()

    /** Auto-rebuilding sending-events snapshot, re-emitting whenever a local echo is added/removed. */
    fun sendingEventsFlow(roomId: String): Flow<List<TimelineEvent>> =
            database.timelineEventQueries.selectSendingByRoom(roomId)
                    .asFlow()
                    .mapToList(dispatcher)
                    .map { sendingEvents(roomId) }

    /** The ignored-user id set, re-emitting whenever the user (un)ignores someone — so the timeline can
     *  re-filter instantly without a re-sync. */
    fun ignoredUserIdsFlow(): Flow<List<String>> =
            database.ignoredUserQueries.selectAll()
                    .asFlow()
                    .mapToList(dispatcher)

    /** Emits when any event's annotation summary (reactions/edits/etc) changes — the chunk flow only watches
     *  timeline_event rows, so it misses these. */
    fun annotationSummaryChangesFlow(roomId: String): Flow<List<String>> =
            database.eventAnnotationsSummaryQueries.selectSummariesForRoom(roomId)
                    .asFlow()
                    .mapToList(dispatcher)
                    .map { rows -> rows.map { it.event_id } }
}
