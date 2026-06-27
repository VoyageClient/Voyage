/*
 * Copyright 2022 The Matrix.org Foundation C.I.C.
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
package org.matrix.android.sdk.internal.session.room.relation.threads

import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.mapper.toEntity
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.EventInsertType
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.events.getFixedRoomMemberContent
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.session.room.relation.RelationsResponse
import org.matrix.android.sdk.internal.session.room.timeline.GetEventTask
import org.matrix.android.sdk.internal.session.room.timeline.PaginationDirection
import org.matrix.android.sdk.internal.task.Task
import org.matrix.android.sdk.internal.util.time.Clock
import timber.log.Timber
import javax.inject.Inject

/***
 * This class is responsible to Fetch paginated chunks of the thread timeline using the /relations API
 *
 * How it works
 *
 * The problem?
 *  - We cannot use the existing timeline architecture to paginate through the timeline
 *  - We want our new events to be live, so any interactions with them like reactions will continue to work. We should
 *    handle appropriately the existing events from /messages api with the new events from /relations.
 *  - Handling edge cases like receiving an event from /messages while you have already created a new one from the /relations response
 *
 * The solution
 * We generate a temporarily thread chunk that will be used to store any new paginated results from the /relations api
 * We bind the timeline events from that chunk with the already existing ones. So we will have one common instance, and
 * all reactions, edits etc will continue to work. If the events do not exists we create them
 * and we will reuse the same EventEntity instance when (and if) the same event will be fetched from the main (/messages) timeline
 *
 */
internal interface FetchThreadTimelineTask : Task<FetchThreadTimelineTask.Params, DefaultFetchThreadTimelineTask.Result> {
    data class Params(
            val roomId: String,
            val rootThreadEventId: String,
            val from: String?,
            val limit: Int

    )
}

internal class DefaultFetchThreadTimelineTask @Inject constructor(
        private val roomAPI: RoomAPI,
        private val globalErrorReceiver: GlobalErrorReceiver,
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val clock: Clock,
        private val getEventTask: GetEventTask,
) : FetchThreadTimelineTask {

    enum class Result {
        SHOULD_FETCH_MORE,
        REACHED_END,
        SUCCESS
    }

    override suspend fun execute(params: FetchThreadTimelineTask.Params): Result {
        val response = executeRequest(globalErrorReceiver) {
            roomAPI.getRelations(
                    roomId = params.roomId,
                    eventId = params.rootThreadEventId,
                    relationType = RelationType.THREAD,
                    from = params.from,
                    limit = params.limit,
            )
        }

        Timber.i("###THREADS FetchThreadTimelineTask Fetched size:${response.chunks.size} nextBatch:${response.nextBatch} ")
        return handleRelationsResponse(response, params)
    }

    private suspend fun handleRelationsResponse(
            response: RelationsResponse,
            params: FetchThreadTimelineTask.Params
    ): Result {
        val threadList = response.chunks
        val hasReachEnd = response.nextBatch == null

        var threadRootEvent: Event? = null
        if (hasReachEnd) {
            val rootKnown = stores.timelineEvent.getByRoomAndEventId(params.roomId, params.rootThreadEventId) != null
            if (!rootKnown) {
                // Fetch the root event from the server
                threadRootEvent = tryOrNull {
                    getEventTask.execute(GetEventTask.Params(roomId = params.roomId, eventId = params.rootThreadEventId))
                }
            }
        }

        database.awaitDbTransaction(dispatcher) {
            val threadChunkId = stores.chunk.lastForwardThread(params.roomId, params.rootThreadEventId)?.id
                    ?: return@awaitDbTransaction

            stores.chunk.updatePrevToken(threadChunkId, response.nextBatch)
            val roomMemberContentsByUser = HashMap<String, RoomMemberContent?>()

            for (event in threadList) {
                if (event.eventId == null || event.senderId == null || event.type == null) continue
                if (stores.timelineEvent.getInChunkByEventId(threadChunkId, event.eventId) != null) {
                    Timber.i("###THREADS event ${event.eventId} already in thread chunk, skip")
                    continue
                }
                val (eventDbId, entity) = insertOrGetEvent(params.roomId, event)
                addSenderState(roomMemberContentsByUser, params.roomId, event.senderId)
                stores.timelineWriter.addTimelineEvent(
                        chunkId = threadChunkId,
                        roomId = params.roomId,
                        eventDbId = eventDbId,
                        event = entity,
                        isLastForward = true,
                        direction = PaginationDirection.FORWARDS,
                        ownedByThreadChunk = true,
                        roomMemberContentsByUser = roomMemberContentsByUser,
                )
            }

            if (hasReachEnd && stores.timelineEvent.getInChunkByEventId(threadChunkId, params.rootThreadEventId) == null) {
                val existingRootDbId = stores.event.getDbId(params.roomId, params.rootThreadEventId)
                when {
                    existingRootDbId != null -> {
                        val rootEntity = stores.event.getById(existingRootDbId)
                        if (rootEntity != null) {
                            rootEntity.sender?.let { addSenderState(roomMemberContentsByUser, params.roomId, it) }
                            stores.timelineWriter.addTimelineEvent(
                                    chunkId = threadChunkId, roomId = params.roomId, eventDbId = existingRootDbId, event = rootEntity,
                                    isLastForward = true, direction = PaginationDirection.FORWARDS, ownedByThreadChunk = true,
                                    roomMemberContentsByUser = roomMemberContentsByUser,
                            )
                        }
                    }
                    threadRootEvent?.senderId != null -> {
                        val (rootDbId, rootEntity) = insertOrGetEvent(params.roomId, threadRootEvent)
                        addSenderState(roomMemberContentsByUser, params.roomId, threadRootEvent.senderId!!)
                        stores.timelineWriter.addTimelineEvent(
                                chunkId = threadChunkId, roomId = params.roomId, eventDbId = rootDbId, event = rootEntity,
                                isLastForward = true, direction = PaginationDirection.FORWARDS, ownedByThreadChunk = true,
                                roomMemberContentsByUser = roomMemberContentsByUser,
                        )
                    }
                }
            }
        }

        return if (hasReachEnd) Result.REACHED_END else Result.SHOULD_FETCH_MORE
    }

    /** Ensure the event exists in the `event` table (insert if new), returning its db id + entity. */
    private fun insertOrGetEvent(roomId: String, event: Event): Pair<Long, EventEntity> {
        val ageLocalTs = clock.epochMillis() - (event.unsignedData?.age ?: 0)
        val entity = event.toEntity(roomId, SendState.SYNCED, ageLocalTs)
        val existing = stores.event.getDbId(roomId, entity.eventId)
        val dbId = existing ?: run {
            stores.eventInsert.insert(entity.eventId, entity.type, canBeProcessed = true, insertType = EventInsertType.PAGINATION)
            stores.event.insert(entity)
        }
        return dbId to (stores.event.getById(dbId) ?: entity)
    }

    /** If we don't have any new state on this user, get it from db. */
    private fun addSenderState(byUser: HashMap<String, RoomMemberContent?>, roomId: String, senderId: String) {
        byUser.getOrPut(senderId) {
            stores.currentStateEvent.getOne(roomId, EventType.STATE_ROOM_MEMBER, senderId)
                    ?.root?.asDomain()
                    ?.getFixedRoomMemberContent()
        }
    }
}
