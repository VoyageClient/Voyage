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
import org.matrix.android.sdk.api.session.events.model.Event
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

    suspend fun insertInDb(
            receivedChunk: TokenChunkEvent,
            roomId: String,
            direction: PaginationDirection,
            originChunkId: Long? = null,
    ): Result {
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
            val existingChunk = stores.chunk.findByTokens(roomId, prevToken, nextToken)
            if (existingChunk != null) {
                // The page is already stored (e.g. as an island from a jump-to-event). Link the chunk
                // we paginated from to it so its history becomes reachable — homeserver boundary tokens
                // don't reliably match ours (Synapse appends a stream suffix), so link by the known
                // origin rather than tokens.
                linkOriginToChunk(direction, originChunkId, existingChunk.id)
                return@awaitDbTransaction
            }
            val prevChunk = stores.chunk.findByNextToken(roomId, prevToken)
            val nextChunk = stores.chunk.findByPrevToken(roomId, nextToken)
            val currentChunkId = stores.chunk.insert(
                    roomId, prevToken, nextToken, prevChunk?.id, nextChunk?.id,
                    isLastForward = false, isLastBackward = false, rootThreadEventId = null, isLastForwardThread = false,
            )
            nextChunk?.let { stores.chunk.updatePrevChunkId(it.id, currentChunkId) }
            prevChunk?.let { stores.chunk.updateNextChunkId(it.id, currentChunkId) }

            if (receivedChunk.events.isEmpty() && !receivedChunk.hasMore()) {
                handleReachEnd(roomId, direction, currentChunkId)
            } else {
                handlePagination(roomId, direction, receivedChunk, currentChunkId)
            }
        }
        return if (receivedChunk.events.isEmpty()) {
            if (receivedChunk.hasMore()) Result.SHOULD_FETCH_MORE else Result.REACHED_END
        } else {
            Result.SUCCESS
        }
    }

    // Link the chunk we paginated from (origin) to the chunk the page resolved to (target), in the
    // pagination direction. Cycle-safe: never link a chunk to itself, and never create a 2-cycle where
    // both chunks point at each other along the same walk (the pagination-hang signature).
    private fun linkOriginToChunk(direction: PaginationDirection, originChunkId: Long?, targetChunkId: Long) {
        if (originChunkId == null || originChunkId == targetChunkId) return
        val origin = stores.chunk.getById(originChunkId) ?: return
        val target = stores.chunk.getById(targetChunkId) ?: return
        if (direction == PaginationDirection.BACKWARDS) {
            // origin is newer, target is older: origin.prev = target, target.next = origin.
            if (origin.next_chunk_id == targetChunkId || target.prev_chunk_id == originChunkId) return
            stores.chunk.updatePrevChunkId(originChunkId, targetChunkId)
            stores.chunk.updateNextChunkId(targetChunkId, originChunkId)
        } else {
            // origin is older, target is newer: origin.next = target, target.prev = origin.
            if (origin.prev_chunk_id == targetChunkId || target.next_chunk_id == originChunkId) return
            stores.chunk.updateNextChunkId(originChunkId, targetChunkId)
            stores.chunk.updatePrevChunkId(targetChunkId, originChunkId)
        }
    }

    private fun handleReachEnd(roomId: String, direction: PaginationDirection, currentChunkId: Long) {
        Timber.v("Reach end of $roomId in $direction")
        if (direction == PaginationDirection.FORWARDS) {
            stores.chunk.updateNextChunkId(currentChunkId, stores.chunk.lastForward(roomId)?.id)
        } else {
            stores.chunk.setLastBackward(currentChunkId, true)
        }
    }

    private fun handlePagination(roomId: String, direction: PaginationDirection, receivedChunk: TokenChunkEvent, currentChunkId: Long) {
        val roomMemberContentsByUser = HashMap<String, RoomMemberContent?>()
        val now = clock.epochMillis()

        receivedChunk.stateEvents?.forEach { stateEvent ->
            val ageLocalTs = now - (stateEvent.unsignedData?.age ?: 0)
            val entity = stateEvent.toEntity(roomId, SendState.SYNCED, ageLocalTs)
            val dbId = insertEventOrIgnore(entity, EventInsertType.PAGINATION)
            if (direction == PaginationDirection.FORWARDS) stores.chunk.addStateEvent(currentChunkId, dbId)
            val stateKey = stateEvent.stateKey
            if (stateEvent.type == EventType.STATE_ROOM_MEMBER && stateKey != null) {
                roomMemberContentsByUser[stateKey] = stateEvent.content.toModel<RoomMemberContent>()
            }
        }
        for (event in receivedChunk.events) {
            val eventId = event.eventId
            if (eventId == null || event.senderId == null || event.type == null) continue
            // A pagination response overlaps events already stored in another chunk (server token
            // boundaries don't align with ours) — typically the boundary event(s) at the near end of
            // the page. Skip just those to avoid duplicating them in the timeline, but keep going:
            // the rest of the page is genuinely new (older/newer) history. Stopping at the first
            // overlap would drop the whole page and leave an empty chunk.
            if (stores.chunk.findMainChunkIdIncludingEvent(roomId, eventId)?.let { it != currentChunkId } == true) {
                continue
            }
            val ageLocalTs = now - (event.unsignedData?.age ?: 0)
            val entity = event.toEntity(roomId, SendState.SYNCED, ageLocalTs)
            val dbId = insertEventOrIgnore(entity, EventInsertType.PAGINATION)
            val stateKey = event.stateKey
            if (event.type == EventType.STATE_ROOM_MEMBER && stateKey != null) {
                val contentToUse = if (direction == PaginationDirection.BACKWARDS) event.prevContent else event.content
                roomMemberContentsByUser[stateKey] = contentToUse.toModel<RoomMemberContent>()
            }
            liveEventManager.get().dispatchPaginatedEventReceived(event, roomId)
            stores.timelineWriter.addTimelineEvent(currentChunkId, roomId, dbId, entity, isLastForward = false, direction, roomMemberContentsByUser = roomMemberContentsByUser)
        }
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
