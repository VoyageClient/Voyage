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

    suspend fun insertInDb(receivedChunk: TokenChunkEvent, roomId: String, direction: PaginationDirection): Result {
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
            if (stores.chunk.findByTokens(roomId, prevToken, nextToken) != null) {
                Timber.v("This chunk is already in the db, return.")
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
        receivedChunk.events.forEach { event ->
            if (event.eventId == null || event.senderId == null || event.type == null) return@forEach
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
        stores.event.getDbId(entity.roomId, entity.eventId)?.let { return it }
        stores.eventInsert.insert(entity.eventId, entity.type, canBeProcessed = true, insertType = insertType)
        return stores.event.insert(entity)
    }
}
