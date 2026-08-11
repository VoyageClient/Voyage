/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync

import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.sync.model.RoomSync
import org.matrix.android.sdk.api.session.sync.model.RoomsSyncResponse
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.events.getFixedRoomMemberContent
import org.matrix.android.sdk.internal.session.sync.handler.SyncResponsePostTreatmentAggregatorHandler
import org.matrix.android.sdk.internal.session.sync.handler.room.SqlRoomSyncHandler
import org.matrix.android.sdk.internal.task.Task
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

internal interface SyncRemovedRoomsTask : Task<SyncRemovedRoomsTask.Params, Unit> {
    data class Params(
            val force: Boolean = false,
    )
}

/**
 * One-shot `include_leave` sync that recovers rooms the user was kicked or banned from on a fresh
 * login / rebuilt store, where the regular sync stream never mentions them again. Only kick/ban
 * rooms are imported (voluntary leaves stay forgotten locally); the response's `prev_batch` seeds
 * back-pagination so their history stays reachable through /messages.
 */
@SessionScope
internal class DefaultSyncRemovedRoomsTask @Inject constructor(
        private val syncAPI: SyncAPI,
        private val globalErrorReceiver: GlobalErrorReceiver,
        private val roomSyncHandler: SqlRoomSyncHandler,
        private val aggregatorHandler: SyncResponsePostTreatmentAggregatorHandler,
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val sessionDbDispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        @UserId private val userId: String,
) : SyncRemovedRoomsTask {

    private val inFlight = AtomicBoolean(false)

    override suspend fun execute(params: SyncRemovedRoomsTask.Params) {
        // The persisted marker lives in the session store, so recovery re-runs exactly when the
        // store is rebuilt (fresh login, schema bump) — the only times its data can be missing.
        if (!params.force && stores.syncToken.isRemovedRoomsRecovered()) return
        if (!inFlight.compareAndSet(false, true)) return
        try {
            val response = executeRequest(globalErrorReceiver) {
                syncAPI.sync(params = mapOf("timeout" to "0", "filter" to FILTER))
            }
            val toImport = response.rooms?.leave.orEmpty().filter { (roomId, roomSync) ->
                isImportable(roomId, roomSync)
            }
            Timber.i("SyncRemovedRooms: importing ${toImport.size} kicked/banned room(s)")
            val aggregator = SyncResponsePostTreatmentAggregator()
            database.awaitDbTransaction(sessionDbDispatcher) {
                if (toImport.isNotEmpty()) {
                    roomSyncHandler.handle(stores, RoomsSyncResponse(leave = toImport), isInitialSync = false, aggregator = aggregator)
                }
                stores.syncToken.markRemovedRoomsRecovered()
            }
            if (toImport.isNotEmpty()) {
                aggregatorHandler.handle(aggregator)
            }
        } finally {
            inFlight.set(false)
        }
    }

    private fun isImportable(roomId: String, roomSync: RoomSync): Boolean {
        // Never touch a room with live local state; this task only backfills unknown removals.
        val localMembership = stores.room.get(roomId)?.membership
        if (localMembership != null && localMembership.isActive()) return false
        val memberEvent = myMemberEvent(roomSync) ?: return false
        val membership = memberEvent.getFixedRoomMemberContent()?.membership
        return membership == Membership.BAN || (membership == Membership.LEAVE && memberEvent.senderId != userId)
    }

    private fun myMemberEvent(roomSync: RoomSync): Event? {
        return sequenceOf(
                roomSync.stateAfter?.events.orEmpty(),
                roomSync.timeline?.events.orEmpty().asReversed(),
                roomSync.state?.events.orEmpty().asReversed(),
        )
                .flatten()
                .firstOrNull { it.type == EventType.STATE_ROOM_MEMBER && it.stateKey == userId }
    }

    private companion object {
        // timeline limit 1 keeps the response small (it also re-includes every joined room);
        // opening a recovered room back-paginates from the chunk's prev_batch instead.
        private const val FILTER =
                """{"room":{"include_leave":true,"timeline":{"limit":1,"lazy_load_members":true},"state":{"lazy_load_members":true}},"presence":{"types":[]}}"""
    }
}
