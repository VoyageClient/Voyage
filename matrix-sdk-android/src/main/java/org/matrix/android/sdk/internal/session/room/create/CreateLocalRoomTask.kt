/*
 * Copyright 2022 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.room.create

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.matrix.android.sdk.api.session.crypto.CryptoService
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.failure.CreateRoomFailure
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.room.model.create.CreateRoomParams
import org.matrix.android.sdk.api.session.room.model.localecho.RoomLocalEcho
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.sync.model.RoomSyncSummary
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.mapper.toEntity
import org.matrix.android.sdk.internal.database.model.EventInsertType
import org.matrix.android.sdk.internal.database.model.LocalRoomSummaryEntity
import org.matrix.android.sdk.internal.database.model.RoomEntity
import org.matrix.android.sdk.internal.database.model.RoomMembersLoadStatusType
import org.matrix.android.sdk.internal.database.model.RoomSummaryEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.database.sqldelight.awaitNotEmptyResult
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.events.getFixedRoomMemberContent
import org.matrix.android.sdk.internal.session.room.membership.SqlRoomMemberEventHandler
import org.matrix.android.sdk.internal.session.room.summary.SqlRoomSummaryUpdater
import org.matrix.android.sdk.internal.session.room.timeline.PaginationDirection
import org.matrix.android.sdk.internal.task.Task
import org.matrix.android.sdk.internal.util.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject

internal interface CreateLocalRoomTask : Task<CreateRoomParams, String>

internal class DefaultCreateLocalRoomTask @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val roomMemberEventHandler: SqlRoomMemberEventHandler,
        private val roomSummaryUpdater: SqlRoomSummaryUpdater,
        private val createRoomBodyBuilder: CreateRoomBodyBuilder,
        private val cryptoService: CryptoService,
        private val clock: Clock,
        private val createLocalRoomStateEventsTask: CreateLocalRoomStateEventsTask,
) : CreateLocalRoomTask {

    override suspend fun execute(params: CreateRoomParams): String {
        val createRoomBody = createRoomBodyBuilder.build(params)
        val roomId = RoomLocalEcho.createLocalEchoId()
        val eventList = createLocalRoomStateEventsTask.execute(CreateLocalRoomStateEventsTask.Params(createRoomBody))
        database.awaitDbTransaction(dispatcher) {
            createLocalRoomEntity(roomId, eventList)
            createLocalRoomSummaryEntity(roomId, params, createRoomBody)
        }

        // Wait for room to be created in DB
        try {
            awaitNotEmptyResult(
                    query = database.roomSummaryQueries.selectByRoomIdAndMembership(roomId, Membership.JOIN.name),
                    timeoutMillis = TimeUnit.MINUTES.toMillis(1L),
                    dispatcher = dispatcher,
            )
        } catch (exception: TimeoutCancellationException) {
            throw CreateRoomFailure.CreatedWithTimeout(roomId)
        }

        return roomId
    }

    /**
     * Create a local room entity from the given room creation params.
     * This will also generate and store in database the chunk and the events related to the room params in order to retrieve and display the local room.
     */
    private fun createLocalRoomEntity(roomId: String, localStateEventList: List<Event>) {
        val chunkId = stores.chunk.insert(
                roomId = roomId, prevToken = null, nextToken = null, prevChunkId = null, nextChunkId = null,
                isLastForward = true, isLastBackward = true, rootThreadEventId = null, isLastForwardThread = false,
        )
        addLocalRoomEvents(chunkId, roomId, localStateEventList)
        val roomEntity = stores.room.get(roomId) ?: RoomEntity(roomId = roomId)
        roomEntity.membership = Membership.JOIN
        roomEntity.membersLoadStatus = RoomMembersLoadStatusType.LOADED
        stores.room.upsert(roomEntity)
    }

    private fun createLocalRoomSummaryEntity(roomId: String, createRoomParams: CreateRoomParams, createRoomBody: CreateRoomBody) {
        // Create the room summary entity
        val roomSummaryEntity = (stores.roomSummary.get(roomId) ?: RoomSummaryEntity(roomId = roomId)).apply {
            val otherUserId = createRoomBody.getDirectUserId()
            if (otherUserId != null) {
                isDirect = true
                directUserId = otherUserId
            }
        }
        stores.roomSummary.upsert(roomSummaryEntity)

        // Update the createRoomParams from the potential feature preset before saving
        createRoomParams.featurePreset?.let { featurePreset ->
            featurePreset.updateRoomParams(createRoomParams)
            createRoomParams.initialStates.addAll(featurePreset.setupInitialStates().orEmpty())
        }

        // Create a LocalRoomSummaryEntity decorated by the related RoomSummaryEntity and the updated CreateRoomParams
        val localRoomSummary = LocalRoomSummaryEntity(roomId = roomId).also {
            it.roomSummaryEntity = roomSummaryEntity
            it.createRoomParams = createRoomParams
        }
        stores.localRoomSummary.upsert(localRoomSummary)

        // Update the RoomSummaryEntity by simulating a fake sync response
        roomSummaryUpdater.update(
                stores = stores,
                roomId = roomId,
                membership = Membership.JOIN,
                roomSummary = RoomSyncSummary(
                        heroes = createRoomBody.invitedUserIds.orEmpty().take(5),
                        joinedMembersCount = 1,
                        invitedMembersCount = createRoomBody.invitedUserIds?.size ?: 0
                ),
                updateMembers = !createRoomBody.invitedUserIds.isNullOrEmpty()
        )
    }

    /**
     * Create a single chunk containing the necessary events to display the local room.
     *
     * @param realm the current instance of realm
     * @param roomId the id of the local room
     * @param localStateEventList list of local state events for that room
     *
     * @return a chunk entity
     */
    private fun addLocalRoomEvents(chunkId: Long, roomId: String, localStateEventList: List<Event>) {
        val roomMemberContentsByUser = HashMap<String, RoomMemberContent?>()

        for (event in localStateEventList) {
            val eventId = event.eventId
            val senderId = event.senderId
            val eventType = event.type
            if (eventId == null || senderId == null || eventType == null) {
                continue
            }

            val now = clock.epochMillis()
            val entity = event.toEntity(roomId, SendState.SYNCED, now)
            val eventDbId = stores.event.getDbId(roomId, entity.eventId) ?: run {
                stores.eventInsert.insert(entity.eventId, entity.type, canBeProcessed = true, insertType = EventInsertType.INCREMENTAL_SYNC)
                stores.event.insert(entity)
            }
            val stateKey = event.stateKey
            if (stateKey != null) {
                stores.currentStateEvent.upsert(roomId, eventType, stateKey, eventId, eventId)
                if (eventType == EventType.STATE_ROOM_MEMBER) {
                    roomMemberContentsByUser[stateKey] = event.getFixedRoomMemberContent()
                    roomMemberEventHandler.handle(stores, roomId, event, false)
                }

                // Give info to crypto module
                runBlocking {
                    cryptoService.onStateEvent(roomId, event, null)
                }
            }

            roomMemberContentsByUser.getOrPut(senderId) {
                // If we don't have any new state on this user, get it from db
                stores.currentStateEvent.getOne(roomId, EventType.STATE_ROOM_MEMBER, senderId)
                        ?.root?.asDomain()?.getFixedRoomMemberContent()
            }

            stores.timelineWriter.addTimelineEvent(
                    chunkId = chunkId,
                    roomId = roomId,
                    eventDbId = eventDbId,
                    event = entity,
                    isLastForward = true,
                    direction = PaginationDirection.FORWARDS,
                    roomMemberContentsByUser = roomMemberContentsByUser,
            )
        }
    }
}
