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
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.failure.CreateRoomFailure
import org.matrix.android.sdk.api.session.room.model.LocalRoomCreationState
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.create.CreateRoomParams
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.database.sqldelight.awaitNotEmptyResult
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.room.summary.RoomSummaryDataSource
import org.matrix.android.sdk.internal.task.Task
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Create a room on the server from a local room.
 * The configuration of the local room will be use to configure the new room.
 * The potential local room members will also be invited to this new room.
 */
internal interface CreateRoomFromLocalRoomTask : Task<CreateRoomFromLocalRoomTask.Params, String> {
    data class Params(val localRoomId: String)
}

internal class DefaultCreateRoomFromLocalRoomTask @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val createRoomTask: CreateRoomTask,
        private val roomSummaryDataSource: RoomSummaryDataSource,
) : CreateRoomFromLocalRoomTask {

    override suspend fun execute(params: CreateRoomFromLocalRoomTask.Params): String {
        val localRoomSummary = roomSummaryDataSource.getLocalRoomSummary(params.localRoomId)
                ?: error("## CreateRoomFromLocalRoomTask - Cannot retrieve LocalRoomSummary with roomId ${params.localRoomId}")

        // If a room has already been created for the given local room, return the existing roomId
        val replacementRoomId = localRoomSummary.replacementRoomId
        if (replacementRoomId != null) {
            return replacementRoomId
        }

        val createRoomParams = localRoomSummary.createRoomParams
        val roomSummary = localRoomSummary.roomSummary
        if (createRoomParams != null && roomSummary != null) {
            return createRoom(params.localRoomId, roomSummary, createRoomParams)
        } else {
            error("## CreateRoomFromLocalRoomTask - Invalid LocalRoomSummary: $localRoomSummary")
        }
    }

    /**
     * Create a room on the server for the given local room.
     *
     * @param localRoomId the local room identifier.
     * @param localRoomSummary the RoomSummary of the local room.
     * @param createRoomParams the CreateRoomParams object which was used to configure the local room.
     *
     * @return the identifier of the created room.
     */
    private suspend fun createRoom(localRoomId: String, localRoomSummary: RoomSummary, createRoomParams: CreateRoomParams): String {
        updateCreationState(localRoomId, LocalRoomCreationState.CREATING)
        val replacementRoomId = runCatching {
            createRoomTask.execute(createRoomParams)
        }.fold(
                { it },
                {
                    updateCreationState(localRoomId, LocalRoomCreationState.FAILURE)
                    throw it
                }
        )
        updateReplacementRoomId(localRoomId, replacementRoomId)
        waitForRoomEvents(replacementRoomId, localRoomSummary)
        updateCreationState(localRoomId, LocalRoomCreationState.CREATED)
        return replacementRoomId
    }

    /**
     * Wait for all the room events before triggering the created state.
     *
     * @param replacementRoomId the identifier of the created room
     * @param localRoomSummary the RoomSummary of the local room.
     */
    private suspend fun waitForRoomEvents(replacementRoomId: String, localRoomSummary: RoomSummary) {
        try {
            awaitNotEmptyResult(
                    query = database.roomSummaryQueries.selectByRoomIdAndInvitedCount(replacementRoomId, localRoomSummary.invitedMembersCount?.toLong()),
                    timeoutMillis = TimeUnit.MINUTES.toMillis(1L),
                    dispatcher = dispatcher,
            )
            awaitNotEmptyResult(
                    query = database.eventQueries.selectByRoomAndType(replacementRoomId, EventType.STATE_ROOM_HISTORY_VISIBILITY),
                    timeoutMillis = TimeUnit.MINUTES.toMillis(1L),
                    dispatcher = dispatcher,
            )
            if (localRoomSummary.isEncrypted) {
                awaitNotEmptyResult(
                        query = database.eventQueries.selectByRoomAndType(replacementRoomId, EventType.STATE_ROOM_ENCRYPTION),
                        timeoutMillis = TimeUnit.MINUTES.toMillis(1L),
                        dispatcher = dispatcher,
                )
            }
        } catch (exception: TimeoutCancellationException) {
            updateCreationState(localRoomSummary.roomId, LocalRoomCreationState.FAILURE)
            throw CreateRoomFailure.CreatedWithTimeout(replacementRoomId)
        }
    }

    private fun updateCreationState(roomId: String, creationState: LocalRoomCreationState) {
        runBlocking {
            database.awaitDbTransaction(dispatcher) {
                stores.localRoomSummary.get(roomId)?.let {
                    it.creationState = creationState
                    stores.localRoomSummary.upsert(it)
                }
            }
        }
    }

    private fun updateReplacementRoomId(localRoomId: String, replacementRoomId: String) {
        runBlocking {
            database.awaitDbTransaction(dispatcher) {
                stores.localRoomSummary.get(localRoomId)?.let {
                    it.replacementRoomId = replacementRoomId
                    stores.localRoomSummary.upsert(it)
                }
            }
        }
    }
}
