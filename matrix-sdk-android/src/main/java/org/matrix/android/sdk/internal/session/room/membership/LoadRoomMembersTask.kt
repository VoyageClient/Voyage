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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.session.crypto.CryptoService
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.internal.crypto.CryptoSessionInfoProvider
import org.matrix.android.sdk.internal.crypto.EncryptedStateEvents
import org.matrix.android.sdk.internal.database.mapper.toEntity
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.EventInsertType
import org.matrix.android.sdk.internal.database.model.RoomEntity
import org.matrix.android.sdk.internal.database.model.RoomMembersLoadStatusType
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.room.RoomAPI
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
            val excludeMembership: Membership? = null,
            /** Fetch full state for an opened sliding-sync room, never for room-list rows. */
            val fullState: Boolean = false,
    )
}

@SessionScope
internal class DefaultLoadRoomMembersTask @Inject constructor(
        private val roomAPI: RoomAPI,
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val syncTokenStore: SyncTokenStore,
        private val roomSummaryUpdater: SqlRoomSummaryUpdater,
        private val roomMemberEventHandler: SqlRoomMemberEventHandler,
        private val cryptoSessionInfoProvider: CryptoSessionInfoProvider,
        private val cryptoService: Lazy<CryptoService>,
        private val globalErrorReceiver: GlobalErrorReceiver,
        private val clock: Clock,
) : LoadRoomMembersTask {

    private val roomLocks = mutableMapOf<String, Mutex>()
    private val importPermit = Semaphore(1)

    // The persisted LOADED marker covers members only; sliding sync also needs the rest of the state.
    private val fullStateLoadedRoomIds = Collections.synchronizedSet(HashSet<String>())

    override suspend fun execute(params: LoadRoomMembersTask.Params) {
        val lock = synchronized(roomLocks) { roomLocks.getOrPut(params.roomId) { Mutex() } }
        lock.withLock {
            val loaded = withContext(dispatcher) { stores.room.get(params.roomId)?.membersLoadStatus == RoomMembersLoadStatusType.LOADED }
            if (!loaded) fullStateLoadedRoomIds.remove(params.roomId)
            val owesFullState = params.fullState && syncTokenStore.getSlidingSyncPos() != null && params.roomId !in fullStateLoadedRoomIds
            if (!owesFullState && loaded) return
            doRequest(params)
        }
    }

    private suspend fun doRequest(params: LoadRoomMembersTask.Params) {
        var imported = false
        try {
            setRoomMembersLoadStatus(params.roomId, RoomMembersLoadStatusType.LOADING)

            // Departed rooms reject sync tokens newer than their leave.
            val (lastToken, stateBeforeRequest) = withContext(dispatcher) {
                syncTokenStore.getLastToken().takeIf { stores.room.get(params.roomId)?.membership == Membership.JOIN } to
                        database.currentStateEventQueries.selectStateIdsByRoom(params.roomId).executeAsList()
                                .associate { (it.type to it.state_key) to it.event_id }
            }
            val loadFullState = params.fullState && syncTokenStore.getSlidingSyncPos() != null
            val response = try {
                executeRequest(globalErrorReceiver) {
                    if (loadFullState) {
                        RoomMembersResponse(roomAPI.getRoomState(params.roomId))
                    } else {
                        roomAPI.getMembers(params.roomId, lastToken, null, params.excludeMembership)
                    }
                }
            } catch (throwable: Throwable) {
                val refusedWhileRemoved = throwable is Failure.ServerError &&
                        throwable.error.code == MatrixError.M_FORBIDDEN &&
                        withContext(dispatcher) { stores.room.get(params.roomId)?.membership != Membership.JOIN }
                if (!refusedWhileRemoved) throw throwable
                // Removed rooms may permanently refuse membership and full-state requests.
                withContext(NonCancellable) {
                    setRoomMembersLoadStatus(params.roomId, RoomMembersLoadStatusType.LOADED)
                    if (loadFullState) fullStateLoadedRoomIds.add(params.roomId)
                }
                return
            }
            // Keep downloaded state when the room screen closes.
            withContext(NonCancellable) {
                importPermit.withPermit { insertInDb(response, params.roomId, stateBeforeRequest) }
                if (loadFullState) fullStateLoadedRoomIds.add(params.roomId)
                imported = true
            }
        } catch (throwable: Throwable) {
            if (!imported) withContext(NonCancellable) { setRoomMembersLoadStatus(params.roomId, RoomMembersLoadStatusType.NONE) }
            throw throwable
        }
    }

    private suspend fun insertInDb(response: RoomMembersResponse, roomId: String, stateBeforeRequest: Map<Pair<String, String>, String>) {
        val (knownState, receiptUserIds) = withContext(dispatcher) {
            database.currentStateEventQueries.selectStateIdsByRoom(roomId).executeAsList()
                    .associate { (it.type to it.state_key) to it.event_id } to
                    stores.readReceipt.getReceiptsInRoom(roomId).keys.mapTo(HashSet()) { it.first }
        }
        val pending = withContext(coroutineDispatchers.computation) {
            response.roomMemberEvents.receiptHoldersFirst(receiptUserIds).filter { event ->
                val slot = EncryptedStateEvents.stateSlot(event)
                slot != null && event.eventId != null && knownState[slot] != event.eventId && knownState[slot] == stateBeforeRequest[slot]
            }
        }
        var inserted = 0
        pending.chunked(20).forEach { events ->
            val prepared = withContext(coroutineDispatchers.computation) {
                val now = clock.epochMillis()
                events.mapNotNull { event ->
                    val slot = EncryptedStateEvents.stateSlot(event) ?: return@mapNotNull null
                    PreparedStateEvent(
                            event = event,
                            slot = slot,
                            entity = event.toEntity(roomId, SendState.SYNCED, now - (event.unsignedData?.age ?: 0)),
                    )
                }
            }
            var offset = 0
            while (offset < prepared.size) {
                database.awaitDbTransaction(dispatcher) {
                    val started = System.nanoTime()
                    do {
                        val (event, slot, entity) = prepared[offset++]
                        val current = database.currentStateEventQueries.selectOne(roomId, slot.first, slot.second)
                                .executeAsOneOrNull()?.event_id
                        // Sync can advance this slot while /members or /state is downloading.
                        if (current != entity.eventId && current == stateBeforeRequest[slot]) {
                            if (stores.event.getDbId(roomId, entity.eventId) == null) {
                                stores.eventInsert.insert(entity.eventId, entity.type, canBeProcessed = true, insertType = EventInsertType.PAGINATION)
                                stores.event.insert(entity)
                            }
                            stores.currentStateEvent.upsert(roomId, slot.first, slot.second, entity.eventId, entity.eventId)
                            roomMemberEventHandler.handle(stores, roomId, event, false)
                            inserted++
                        }
                    } while (offset < prepared.size && System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(40))
                }
                // Give sync, sends and drafts a chance to enqueue before the next import transaction.
                delay(1)
            }
        }
        Timber.d("Member import completed: received=%d changed=%d inserted=%d", response.roomMemberEvents.size, pending.size, inserted)
        database.awaitDbTransaction(dispatcher) {
            val roomEntity = stores.room.get(roomId) ?: RoomEntity(roomId = roomId)
            roomEntity.membersLoadStatus = RoomMembersLoadStatusType.LOADED
            stores.room.upsert(roomEntity)
            roomSummaryUpdater.update(stores, roomId, updateMembers = true)
        }
        if (cryptoSessionInfoProvider.isRoomEncrypted(roomId)) {
            cryptoService.get().onE2ERoomMemberLoadedFromServer(roomId)
        }
    }

    private data class PreparedStateEvent(val event: Event, val slot: Pair<String, String>, val entity: EventEntity)

    private suspend fun setRoomMembersLoadStatus(roomId: String, status: RoomMembersLoadStatusType) {
        database.awaitDbTransaction(dispatcher) {
            val roomEntity = stores.room.get(roomId) ?: RoomEntity(roomId = roomId)
            roomEntity.membersLoadStatus = status
            stores.room.upsert(roomEntity)
        }
    }
}

/** Receipt-holder profiles are needed before the rest of a large room finishes loading. */
internal fun List<Event>.receiptHoldersFirst(receiptUserIds: Set<String>): List<Event> =
        if (receiptUserIds.isEmpty()) this else partition { it.stateKey in receiptUserIds }.let { it.first + it.second }
