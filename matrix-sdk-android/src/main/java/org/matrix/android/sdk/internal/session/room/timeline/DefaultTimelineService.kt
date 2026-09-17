/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.Flow
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.TimelineService
import org.matrix.android.sdk.api.session.room.timeline.TimelineSettings
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.internal.database.mapper.TimelineEventMapper
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.session.room.membership.LoadRoomMembersTask
import org.matrix.android.sdk.internal.session.room.relation.threads.FetchThreadTimelineTask
import org.matrix.android.sdk.internal.session.room.send.LocalEchoEventFactory
import org.matrix.android.sdk.internal.session.room.state.StateEventDataSource
import org.matrix.android.sdk.internal.session.sync.sliding.SlidingSyncRoomSubscriptions
import org.matrix.android.sdk.internal.util.time.Clock

internal class DefaultTimelineService @AssistedInject constructor(
        @Assisted private val roomId: String,
        private val timelineInput: TimelineInput,
        private val contextOfEventTask: GetContextOfEventTask,
        private val eventDecryptorProvider: javax.inject.Provider<TimelineEventDecryptor>,
        private val paginationTask: PaginationTask,
        private val fetchRoomStartTask: FetchRoomStartTask,
        private val fetchTokenAndPaginateTask: FetchTokenAndPaginateTask,
        private val fetchThreadTimelineTask: FetchThreadTimelineTask,
        private val timelineEventMapper: TimelineEventMapper,
        private val loadRoomMembersTask: LoadRoomMembersTask,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val timelineEventDataSource: SqlTimelineEventDataSource,
        private val clock: Clock,
        private val stateEventDataSource: StateEventDataSource,
        private val localEchoEventFactory: LocalEchoEventFactory,
        private val roomAPI: RoomAPI,
        private val globalErrorReceiver: GlobalErrorReceiver,
        @SessionDatabase private val database: org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase,
        @SessionDatabase private val sessionDbDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        @org.matrix.android.sdk.internal.di.SessionDatabaseTimeline private val readDispatcher: kotlinx.coroutines.CoroutineDispatcher,
        private val stores: org.matrix.android.sdk.internal.database.sql.store.SessionStores,
        private val timelineRedactionSignal: TimelineRedactionSignal,
        private val timelineDecryptionSignal: TimelineDecryptionSignal,
        private val gapHealer: TimelineGapHealer,
        private val slidingSyncRoomSubscriptions: SlidingSyncRoomSubscriptions,
) : TimelineService {

    @AssistedFactory
    interface Factory {
        fun create(roomId: String): DefaultTimelineService
    }

    override fun createTimeline(eventId: String?, settings: TimelineSettings): Timeline {
        val snapshotLoader = SqlChunkSnapshotLoader(database, readDispatcher, stores, timelineEventMapper)
        return SqlTimeline(
                roomId = roomId,
                initialEventId = eventId,
                settings = settings,
                coroutineDispatchers = coroutineDispatchers,
                stores = stores,
                snapshotLoader = snapshotLoader,
                paginationTask = paginationTask,
                fetchThreadTimelineTask = fetchThreadTimelineTask,
                contextOfEventTask = contextOfEventTask,
                fetchRoomStartTask = fetchRoomStartTask,
                database = database,
                sessionDispatcher = sessionDbDispatcher,
                readDispatcher = readDispatcher,
                eventDecryptor = eventDecryptorProvider.get(),
                timelineInput = timelineInput,
                clock = clock,
                redactionSignal = timelineRedactionSignal,
                decryptionSignal = timelineDecryptionSignal,
                loadRoomMembersTask = loadRoomMembersTask,
                gapHealer = gapHealer,
                slidingSyncRoomSubscriptions = slidingSyncRoomSubscriptions,
        )
    }

    override fun getTimelineEvent(eventId: String): TimelineEvent? {
        return timelineEventDataSource.getTimelineEvent(roomId, eventId)
    }

    override suspend fun fetchEventIdForTimestamp(timestampMs: Long, forward: Boolean): String? {
        val dir = if (forward) "f" else "b"
        // Server may not implement MSC3030, may 404 when no event exists in that direction,
        // or the network call might fail; all collapse to null.
        return try {
            executeRequest(globalErrorReceiver) {
                roomAPI.getEventForTimestamp(roomId, timestampMs, dir)
            }.eventId
        } catch (failure: Throwable) {
            null
        }
    }

    override fun getTimelineEventFlow(eventId: String): Flow<Optional<TimelineEvent>> {
        return timelineEventDataSource.getTimelineEventFlow(roomId, eventId)
    }

    override fun getAttachmentMessages(): List<TimelineEvent> {
        return timelineEventDataSource.getAttachmentMessages(roomId)
    }

    override fun getTimelineEventsRelatedTo(relationType: String, eventId: String): List<TimelineEvent> {
        return timelineEventDataSource.getTimelineEventsRelatedTo(roomId, relationType, eventId)
    }

    override fun debugCheckTimeline(): String {
        val chunks = stores.chunk.getByRoom(roomId).filter { it.root_thread_event_id == null }
        val live = chunks.filter { it.is_last_forward == 1L }
        val rowsByChunk = chunks.associate { it.id to stores.timelineEvent.getByChunk(it.id) }
        val failures = mutableListOf<String>()

        if (live.size != 1) failures.add("live ranges: ${live.size} (want 1)")

        // No reachability rule to check: ranges carry no links, so every stored event is addressable by
        // definition. What can still be wrong is two ranges claiming the same history.
        rowsByChunk.filterValues { it.isEmpty() }.keys
                .filterNot { id -> chunks.firstOrNull { it.id == id }?.is_last_forward == 1L }
                .takeIf { it.isNotEmpty() }
                ?.let { failures.add("empty ranges: $it") }
        val sharedEvents = rowsByChunk.entries
                .flatMap { (id, rows) -> rows.map { it.eventId to id } }
                .groupBy({ it.first }, { it.second })
                .filterValues { it.distinct().size > 1 }
        if (sharedEvents.isNotEmpty()) {
            failures.add("${sharedEvents.size} event(s) in more than one range, e.g. ${sharedEvents.entries.take(3)}")
        }

        rowsByChunk.forEach { (chunkId, rows) ->
            rows.groupBy { it.eventId }.filterValues { it.size > 1 }.keys.takeIf { it.isNotEmpty() }?.let {
                failures.add("duplicate events in #$chunkId: ${it.take(5)}")
            }
            // Read back in the order the timeline renders them; anything out of order here is stored wrong,
            // not sorted wrong, since the query is what defines the order.
            val descending = rows.zipWithNext().none { (newer, older) ->
                newer.ts < older.ts || (newer.ts == older.ts && newer.eventId < older.eventId)
            }
            if (!descending) failures.add("#$chunkId does not read back newest first")
            rows.count { it.ts == 0L }.takeIf { it > 0 }?.let { failures.add("$it events with no timestamp in #$chunkId") }
        }

        // Overlapping main ranges can hide history because a timeline binds to one range.
        val spans = rowsByChunk.filterValues { it.isNotEmpty() }
                .mapValues { (_, rows) -> rows.minOf { it.ts } to rows.maxOf { it.ts } }
        spans.entries.sortedBy { it.value.first }.zipWithNext().forEach { (a, b) ->
            if (a.value.first < b.value.second && b.value.first < a.value.second) {
                failures.add("#${a.key} and #${b.key} cover the same period: ${a.value} vs ${b.value}")
            }
        }

        // Timestamp gaps are diagnostic notes: quiet periods and unfillable holes are valid.
        val notes = mutableListOf<String>()
        rowsByChunk.forEach { (chunkId, rows) ->
            rows.zipWithNext()
                    .filter { (newer, older) -> newer.ts - older.ts > INTERNAL_GAP_THRESHOLD_MS }
                    .takeIf { it.isNotEmpty() }
                    ?.let { gaps ->
                        val worst = gaps.maxBy { (newer, older) -> newer.ts - older.ts }
                        notes.add(
                                "#$chunkId spans ${gaps.size} internal gap(s), worst " +
                                        "${(worst.first.ts - worst.second.ts) / 86_400_000}d between" +
                                        " ${worst.second.eventId} and ${worst.first.eventId}"
                        )
                    }
        }

        val events = rowsByChunk.values.sumOf { it.size }
        return buildString {
            append("ranges=${chunks.size} events=$events ")
            append(if (failures.isEmpty()) "VERDICT PASS" else "VERDICT FAIL")
            failures.forEach { append("\n  FAIL $it") }
            notes.forEach { append("\n  NOTE $it") }
        }
    }

    override fun debugDumpChunks(): String {
        val chunks = stores.chunk.getByRoom(roomId)
        val live = chunks.firstOrNull { it.is_last_forward == 1L }
        return buildString {
            append("ranges=${chunks.size} live=${live?.id}\n")
            chunks.sortedByDescending { stores.timelineEvent.maxTsForChunk(it.id) ?: 0 }.forEach { chunk ->
                append("  #${chunk.id}")
                append(" events=${stores.timelineEvent.countByChunk(chunk.id)}")
                append(" ts=${stores.timelineEvent.minTsForChunk(chunk.id)}..${stores.timelineEvent.maxTsForChunk(chunk.id)}")
                append(" live=${chunk.is_last_forward == 1L} reachedStart=${chunk.is_last_backward == 1L}")
                append(" prevToken=${chunk.prev_token?.take(12)} nextToken=${chunk.next_token?.take(12)}")
                append("\n")
            }
        }
    }

    private companion object {
        // Same day threshold the gap healer works to.
        private const val INTERNAL_GAP_THRESHOLD_MS = 24 * 3600 * 1000L
    }
}
