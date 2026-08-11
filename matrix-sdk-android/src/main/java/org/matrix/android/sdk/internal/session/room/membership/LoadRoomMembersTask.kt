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

package org.matrix.android.sdk.internal.session.room.membership

import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.crypto.CryptoService
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.internal.crypto.CryptoSessionInfoProvider
import org.matrix.android.sdk.internal.database.mapper.toEntity
import org.matrix.android.sdk.internal.database.model.EventInsertType
import org.matrix.android.sdk.internal.database.model.RoomEntity
import org.matrix.android.sdk.internal.database.model.RoomMembersLoadStatusType
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.database.sqldelight.awaitNotEmptyResult
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.session.room.RoomDataSource
import org.matrix.android.sdk.internal.session.room.summary.SqlRoomSummaryUpdater
import org.matrix.android.sdk.internal.session.sync.SyncTokenStore
import org.matrix.android.sdk.internal.task.Task
import org.matrix.android.sdk.internal.util.time.Clock
import timber.log.Timber
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.inject.Inject

internal interface LoadRoomMembersTask : Task<LoadRoomMembersTask.Params, Unit> {

    data class Params(
            val roomId: String,
            val excludeMembership: Membership? = null
    )
}

@SessionScope
internal class DefaultLoadRoomMembersTask @Inject constructor(
        private val roomAPI: RoomAPI,
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val roomDataSource: RoomDataSource,
        private val syncTokenStore: SyncTokenStore,
        private val roomSummaryUpdater: SqlRoomSummaryUpdater,
        private val roomMemberEventHandler: SqlRoomMemberEventHandler,
        private val cryptoSessionInfoProvider: CryptoSessionInfoProvider,
        private val cryptoService: Lazy<CryptoService>,
        private val globalErrorReceiver: GlobalErrorReceiver,
        private val clock: Clock,
) : LoadRoomMembersTask {

    // Rooms with a /members request genuinely in flight in this process. A persisted LOADING marker alone can't
    // be trusted: a request cancelled with its caller's scope (or an app killed mid-load) leaves LOADING behind
    // with nothing running, which would otherwise wedge the member list until a 1-minute timeout on each reopen.
    private val inFlightRoomIds = Collections.synchronizedSet(HashSet<String>())

    override suspend fun execute(params: LoadRoomMembersTask.Params) {
        when {
            roomDataSource.getRoomMembersLoadStatus(params.roomId) == RoomMembersLoadStatusType.LOADED -> Unit
            params.roomId in inFlightRoomIds -> waitPreviousRequestToFinish(params)
            // NONE, or a stale LOADING left by a dead/cancelled request — fetch either way.
            else -> doRequest(params)
        }
    }

    private suspend fun waitPreviousRequestToFinish(params: LoadRoomMembersTask.Params) {
        try {
            awaitNotEmptyResult(
                    query = database.roomQueries.selectByRoomIdAndLoadStatus(params.roomId, RoomMembersLoadStatusType.LOADED.name),
                    timeoutMillis = TimeUnit.MINUTES.toMillis(1L),
                    dispatcher = dispatcher,
            )
        } catch (exception: TimeoutCancellationException) {
            doRequest(params)
        }
    }

    private suspend fun doRequest(params: LoadRoomMembersTask.Params) {
        inFlightRoomIds.add(params.roomId)
        try {
            setRoomMembersLoadStatus(params.roomId, RoomMembersLoadStatusType.LOADING)

            // For a room we're no longer in, the current sync token is past our leave and the server
            // refuses it; without `at` it serves the membership as of when we left.
            val lastToken = syncTokenStore.getLastToken()
                    .takeIf { stores.room.get(params.roomId)?.membership == Membership.JOIN }
            val response = try {
                executeRequest(globalErrorReceiver) {
                    roomAPI.getMembers(params.roomId, lastToken, null, params.excludeMembership)
                }
            } catch (throwable: Throwable) {
                // A removed room the server refuses members for will refuse forever; mark it LOADED
                // so the member list settles on the local state instead of retrying.
                val refusedWhileRemoved = throwable is Failure.ServerError &&
                        throwable.error.code == MatrixError.M_FORBIDDEN &&
                        stores.room.get(params.roomId)?.membership != Membership.JOIN
                // NonCancellable so the revert still runs if the caller's scope died mid-request; otherwise the
                // marker stays stuck at LOADING.
                withContext(NonCancellable) {
                    setRoomMembersLoadStatus(
                            params.roomId,
                            if (refusedWhileRemoved) RoomMembersLoadStatusType.LOADED else RoomMembersLoadStatusType.NONE,
                    )
                }
                if (refusedWhileRemoved) return
                throw throwable
            }
            try {
                // This will also set the status to LOADED
                insertInDb(response, params.roomId)
            } catch (throwable: Throwable) {
                withContext(NonCancellable) {
                    setRoomMembersLoadStatus(params.roomId, RoomMembersLoadStatusType.NONE)
                }
                throw throwable
            }
        } finally {
            inFlightRoomIds.remove(params.roomId)
        }
    }

    private suspend fun insertInDb(response: RoomMembersResponse, roomId: String) {
        val chunks = response.roomMemberEvents.chunked(500)
        chunks.forEach { roomMemberEvents ->
            database.awaitDbTransaction(dispatcher) {
                Timber.v("Insert ${roomMemberEvents.size} member events in room $roomId")
                // We ignore all the already known members
                val now = clock.epochMillis()
                for (roomMemberEvent in roomMemberEvents) {
                    val memberEventId = roomMemberEvent.eventId
                    val memberStateKey = roomMemberEvent.stateKey
                    val memberType = roomMemberEvent.type
                    if (memberEventId == null || memberStateKey == null || memberType == null) {
                        continue
                    }
                    val ageLocalTs = now - (roomMemberEvent.unsignedData?.age ?: 0)
                    val entity = roomMemberEvent.toEntity(roomId, SendState.SYNCED, ageLocalTs)
                    if (stores.event.getDbId(roomId, entity.eventId) == null) {
                        stores.eventInsert.insert(entity.eventId, entity.type, canBeProcessed = true, insertType = EventInsertType.PAGINATION)
                        stores.event.insert(entity)
                    }
                    stores.currentStateEvent.upsert(roomId, memberType, memberStateKey, memberEventId, memberEventId)
                    roomMemberEventHandler.handle(stores, roomId, roomMemberEvent, false)
                }
            }
        }
        database.awaitDbTransaction(dispatcher) {
            val roomEntity = stores.room.get(roomId) ?: RoomEntity(roomId = roomId)
            roomEntity.membersLoadStatus = RoomMembersLoadStatusType.LOADED
            stores.room.upsert(roomEntity)
            roomSummaryUpdater.update(stores, roomId, updateMembers = true)
        }
        if (cryptoSessionInfoProvider.isRoomEncrypted(roomId)) {
            cryptoService.get().onE2ERoomMemberLoadedFromServer(roomId)
//            val userIds = cryptoSessionInfoProvider.getRoomUserIds(roomId, true)
//            olmMachineProvider.olmMachine.updateTrackedUsers(userIds)
//            deviceListManager.onRoomMembersLoadedFor(roomId)
        }
    }

    private suspend fun setRoomMembersLoadStatus(roomId: String, status: RoomMembersLoadStatusType) {
        database.awaitDbTransaction(dispatcher) {
            val roomEntity = stores.room.get(roomId) ?: RoomEntity(roomId = roomId)
            roomEntity.membersLoadStatus = status
            stores.room.upsert(roomEntity)
        }
    }
}
