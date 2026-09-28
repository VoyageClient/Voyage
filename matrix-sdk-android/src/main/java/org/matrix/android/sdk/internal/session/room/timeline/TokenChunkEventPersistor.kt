/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.settings.LightweightSettingsStorage
import org.matrix.android.sdk.internal.database.mapper.toEntity
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.EventInsertType
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.StreamEventsManager
import org.matrix.android.sdk.internal.session.events.getFixedRoomMemberContent
import org.matrix.android.sdk.internal.util.time.Clock
import timber.log.Timber
import javax.inject.Inject

/** Insert a paginated Chunk into the SQL DB, linking next/previous chunks (was Realm). */
internal class TokenChunkEventPersistor @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        @UserId private val userId: String,
        private val lightweightSettingsStorage: LightweightSettingsStorage,
        private val liveEventManager: Lazy<StreamEventsManager>,
        private val clock: Clock,
) {

    enum class Result { SHOULD_FETCH_MORE, REACHED_END, SUCCESS }

    // Out-params for the caller's pagination loop: how many timeline rows the page actually produced
    // (a page can be almost entirely overlap-skipped duplicates) and which chunk the walk landed in,
    // so the loop can keep fetching from that chunk's far token until real progress is made.
    class PageWriteStats {
        var written = 0
        var landedChunkId: Long? = null

        /** Ranges this page proved already hold its history, and which were folded in as a result. */
        var folded = 0

        /** Whether writing the page moved rows between ranges, which invalidates any mapping of them. */
        var rowsMoved = false
    }

    suspend fun insertInDb(
            receivedChunk: TokenChunkEvent,
            roomId: String,
            direction: PaginationDirection,
            originChunkId: Long? = null,
            stats: PageWriteStats? = null,
    ): Result {
        var tokenSlideOnly = false
        database.awaitDbTransaction(dispatcher) {
            val nextToken: String?
            val prevToken: String?
            if (direction == PaginationDirection.FORWARDS) {
                nextToken = receivedChunk.end
                prevToken = receivedChunk.start
            } else {
                nextToken = receivedChunk.start
                prevToken = receivedChunk.end
            }
            // A page whose events were all withheld (history visibility over a span we weren't a
            // member for) must not become an empty dead-end chunk: the walk would re-request the
            // same token forever (and, once such a chunk exists, keep short-circuiting on it below).
            // Slide the origin chunk's own token past the invisible span so the next fetch makes
            // progress.
            if (receivedChunk.events.isEmpty() && receivedChunk.hasMore() && originChunkId != null) {
                if (direction == PaginationDirection.BACKWARDS) {
                    stores.chunk.updatePrevToken(originChunkId, prevToken)
                } else {
                    stores.chunk.updateNextToken(originChunkId, nextToken)
                }
                tokenSlideOnly = true
                stats?.landedChunkId = originChunkId
                return@awaitDbTransaction
            }
            // Prefer the origin range, then matching boundary tokens. Merge shared history after insertion.
            val target = originChunkId?.let { stores.chunk.getById(it) }
                    ?: stores.chunk.findByTokens(roomId, prevToken, nextToken)
                    ?: stores.chunk.findByNextToken(roomId, prevToken)
                    ?: stores.chunk.findByPrevToken(roomId, nextToken)
            val currentChunkId = target?.id ?: stores.chunk.insert(
                    roomId, prevToken, nextToken,
                    isLastForward = false, isLastBackward = false, rootThreadEventId = null, isLastForwardThread = false,
            )
            // Advance the frontier we just fetched past, or the next page re-requests this one.
            if (target != null) {
                if (direction == PaginationDirection.BACKWARDS) {
                    stores.chunk.updatePrevToken(currentChunkId, prevToken)
                } else if (target.is_last_forward == 0L) {
                    stores.chunk.updateNextToken(currentChunkId, nextToken)
                }
            }

            stats?.landedChunkId = currentChunkId
            if (receivedChunk.events.isEmpty() && !receivedChunk.hasMore()) {
                handleReachEnd(roomId, direction, currentChunkId)
            } else {
                handlePagination(roomId, direction, receivedChunk, currentChunkId, originChunkId, stats)
            }
            val folded = stores.chunk.mergeRangesSharingEvents(roomId, currentChunkId)
            val overlapping = stores.chunk.mergeOverlappingRanges(roomId)
            if (folded != null || overlapping > 0) {
                stats?.rowsMoved = true
                DebugLog.i { "pagination in $roomId folded shared/overlapping ranges into ${folded ?: currentChunkId}" }
            }
            // Any of those merges can retire the range the page was written to, so the walk continues from
            // wherever the page's own events ended up rather than from an id that may no longer exist.
            receivedChunk.events.firstNotNullOfOrNull { it.eventId }
                    ?.let { stores.chunk.findMainChunkIdIncludingEvent(roomId, it) }
                    ?.let { stats?.landedChunkId = it }
        }
        return when {
            tokenSlideOnly -> Result.SHOULD_FETCH_MORE
            receivedChunk.events.isEmpty() -> if (receivedChunk.hasMore()) Result.SHOULD_FETCH_MORE else Result.REACHED_END
            else -> Result.SUCCESS
        }
    }

    private fun handleReachEnd(roomId: String, direction: PaginationDirection, currentChunkId: Long) {
        Timber.v("Reach end of $roomId in $direction")
        if (direction == PaginationDirection.FORWARDS) {
            // Nothing newer left to fetch, so this range runs up to the live edge: they are one range.
            stores.chunk.lastForward(roomId)?.id?.let { stores.chunk.mergeInto(it, currentChunkId) }
        } else {
            stores.chunk.setLastBackward(currentChunkId, true)
        }
    }

    private fun handlePagination(
            roomId: String,
            direction: PaginationDirection,
            receivedChunk: TokenChunkEvent,
            currentChunkId: Long,
            originChunkId: Long?,
            stats: PageWriteStats? = null,
    ) {
        val roomMemberContentsByUser = HashMap<String, RoomMemberContent?>()
        val roomMemberEventIdsByUser = HashMap<String, String?>()
        val now = clock.epochMillis()

        receivedChunk.stateEvents?.forEach { stateEvent ->
            val ageLocalTs = now - (stateEvent.unsignedData?.age ?: 0)
            val entity = stateEvent.toEntity(roomId, SendState.SYNCED, ageLocalTs)
            insertEventOrIgnore(entity, EventInsertType.PAGINATION)
            val stateKey = stateEvent.stateKey
            if (stateEvent.type == EventType.STATE_ROOM_MEMBER && stateKey != null) {
                roomMemberContentsByUser[stateKey] = stateEvent.content.toModel<RoomMemberContent>()
                roomMemberEventIdsByUser[stateKey] = stateEvent.eventId
            }
        }
        val threadCandidateIds = ArrayList<String>(receivedChunk.events.size)
        val sharedWithOtherRanges = LinkedHashSet<Long>()
        for (event in receivedChunk.events) {
            val eventId = event.eventId
            if (eventId == null || event.senderId == null || event.type == null) continue
            // A pagination response overlaps events already stored in another chunk (server token
            // boundaries don't align with ours) — typically the boundary event(s) at the near end of
            // the page. Skip just those to avoid duplicating them in the timeline, but keep going:
            // the rest of the page is genuinely new (older/newer) history. Stopping at the first
            // overlap would drop the whole page and leave an empty chunk. Exception: when the owner
            // is a lone-event /context island (a jump-to-event chunk), skipping would orphan the
            // event forever — the island never gets two-sidedly linked into the walk — so absorb it
            // into this chunk instead.
            val ownerChunkId = stores.chunk.findMainChunkIdIncludingEvent(roomId, eventId)
            if (ownerChunkId != null && ownerChunkId != currentChunkId) {
                // Never absorb the chunk being paginated from: the caller (and an open timeline seeded
                // on it) still holds its id.
                if (ownerChunkId == originChunkId || !absorbIslandChunk(roomId, ownerChunkId, currentChunkId)) {
                    // Remember duplicate ownership because no inserted row remains for overlap detection.
                    sharedWithOtherRanges.add(ownerChunkId)
                    continue
                }
            }
            val ageLocalTs = now - (event.unsignedData?.age ?: 0)
            val entity = event.toEntity(roomId, SendState.SYNCED, ageLocalTs)
            val dbId = insertEventOrIgnore(entity, EventInsertType.PAGINATION)
            val stateKey = event.stateKey
            // A membership row uses its own profile; older rows on a backward page use prev_content.
            // Keep the current profile when prev_content is missing rather than falling back to live state.
            var profileForOlderEvents: RoomMemberContent? = null
            val isOwnMemberEvent = event.type == EventType.STATE_ROOM_MEMBER && stateKey != null
            if (isOwnMemberEvent) {
                roomMemberContentsByUser[stateKey!!] = event.getFixedRoomMemberContent()
                roomMemberEventIdsByUser[stateKey] = eventId
                profileForOlderEvents = event.prevContent.toModel<RoomMemberContent>()
            }
            liveEventManager.get().dispatchPaginatedEventReceived(event, roomId)
            stats?.let { it.written++ }
            stores.timelineWriter.addTimelineEvent(
                    currentChunkId, roomId, dbId, entity, isLastForward = false,
                    roomMemberContentsByUser = roomMemberContentsByUser,
                    roomMemberEventIdsByUser = roomMemberEventIdsByUser,
            )
            if (isOwnMemberEvent && direction == PaginationDirection.BACKWARDS && profileForOlderEvents != null) {
                roomMemberContentsByUser[stateKey!!] = profileForOlderEvents
            }
            threadCandidateIds.add(eventId)
            entity.rootThreadEventId?.let { threadCandidateIds.add(it) }
        }
        var landedChunkId = currentChunkId
        sharedWithOtherRanges.forEach { other ->
            if (stores.chunk.getById(other) == null || stores.chunk.getById(landedChunkId) == null) return@forEach
            // Keep the larger of the two, not this page's range: the other one can be a long history an
            // open timeline is reading, and folding it away leaves that timeline bound to a dead range.
            val survivor = stores.chunk.mergeKeepingLarger(landedChunkId, other)
            stats?.let { it.folded++; it.rowsMoved = true }
            DebugLog.i { "pagination in $roomId merged ranges $landedChunkId and $other into $survivor" }
            landedChunkId = survivor
        }
        // Threaded replies come back through /messages like any other event, so a cleared cache rebuilds a
        // root's thread badge only here — sync never sees those replies again.
        if (lightweightSettingsStorage.areThreadMessagesEnabled()) {
            stores.markThreadRoots(roomId, threadCandidateIds)
        }
    }

    // Delete the island and hand its graph position to the absorbing chunk, which is about to store
    // the same event at its correct in-page position. Only lone-event, non-live, non-thread chunks
    // qualify: anything bigger is a real parallel window, where dropping the duplicate is right.
    private fun absorbIslandChunk(roomId: String, islandId: Long, absorberId: Long): Boolean {
        val island = stores.chunk.getById(islandId) ?: return false
        if (island.is_last_forward != 0L || island.is_last_backward != 0L ||
                island.is_last_forward_thread != 0L || island.root_thread_event_id != null) {
            return false
        }
        if (stores.timelineEvent.countByChunk(islandId) != 1L) return false
        stores.chunk.mergeInto(absorberId, islandId)
        Timber.i("Absorbed lone-event chunk $islandId into $absorberId in $roomId")
        return true
    }

    private fun insertEventOrIgnore(entity: EventEntity, insertType: EventInsertType): Long {
        stores.event.getDbId(entity.roomId, entity.eventId)?.let { dbId ->
            // An event row can outlive its insert-queue entry (a gappy sync clears chunks but keeps
            // event rows), so a re-delivered relation event may never have been aggregated: its edit
            // or reaction would be missing forever. Re-enqueue it; processing is idempotent.
            if (entity.content?.contains("m.relates_to") == true && !stores.eventInsert.exists(entity.eventId)) {
                stores.eventInsert.insert(entity.eventId, entity.type, canBeProcessed = true, insertType = insertType)
            }
            return dbId
        }
        stores.eventInsert.insert(entity.eventId, entity.type, canBeProcessed = true, insertType = insertType)
        return stores.event.insert(entity)
    }
}
