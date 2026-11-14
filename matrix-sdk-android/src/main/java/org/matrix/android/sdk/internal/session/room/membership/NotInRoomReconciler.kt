/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.membership

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.sync.model.RoomSync
import org.matrix.android.sdk.api.session.sync.model.RoomSyncState
import org.matrix.android.sdk.api.session.sync.model.RoomsSyncResponse
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.SessionCoroutineScopeHolder
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.sync.SyncResponsePostTreatmentAggregator
import org.matrix.android.sdk.internal.session.sync.handler.SyncResponsePostTreatmentAggregatorHandler
import org.matrix.android.sdk.internal.session.sync.handler.room.SqlRoomSyncHandler
import timber.log.Timber
import java.util.Collections
import javax.inject.Inject

/**
 * Some servers never sync a removal: Synapse with `forget_rooms_on_leave` forgets the room as soon as we stop being
 * joined, and a forgotten room is left out of every sync. A 403 from the room is then the only sign, so on one we ask
 * the server for our own membership and apply the answer locally.
 */
@SessionScope
internal class NotInRoomReconciler @Inject constructor(
        private val ownMembershipProbe: OwnMembershipProbe,
        private val roomSyncHandler: SqlRoomSyncHandler,
        private val aggregatorHandler: SyncResponsePostTreatmentAggregatorHandler,
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val sessionDbDispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val sessionCoroutineScopeHolder: SessionCoroutineScopeHolder,
) {

    private val inFlight = Collections.synchronizedSet(mutableSetOf<String>())

    fun onSendFailed(roomId: String, failure: Throwable) {
        if (!failure.isForbidden() || !inFlight.add(roomId)) return
        sessionCoroutineScopeHolder.scope.launch {
            try {
                reconcile(roomId)
            } finally {
                inFlight.remove(roomId)
            }
        }
    }

    /** @return whether the leave can count as done: the server confirmed we were no longer in the room. */
    suspend fun onLeaveFailed(roomId: String, failure: Throwable): Boolean = failure.isForbidden() && reconcile(roomId)

    private suspend fun reconcile(roomId: String): Boolean {
        // The probe cannot tell an invited or knocking user from a departed one, so only a join is checked.
        val localMembership = database.awaitDbTransaction(sessionDbDispatcher) { stores.room.get(roomId)?.membership }
        if (localMembership != Membership.JOIN) return false
        val departed = ownMembershipProbe.probe(roomId) as? OwnMembership.Departed ?: return false
        Timber.w("Not in $roomId although stored as joined (member event: ${departed.memberEvent != null}, membership: ${departed.membership})")
        val aggregator = SyncResponsePostTreatmentAggregator()
        database.awaitDbTransaction(sessionDbDispatcher) {
            val memberEvent = departed.memberEvent
            if (memberEvent != null) {
                // The left-room path tells a kick or ban (kept, Historical) from a leave of our own.
                val roomSync = RoomSync(stateAfterStable = RoomSyncState(events = listOf(memberEvent)))
                roomSyncHandler.handle(stores, RoomsSyncResponse(leave = mapOf(roomId to roomSync)), isInitialSync = false, aggregator = aggregator)
            } else {
                val membership = if (departed.membership == Membership.BAN) Membership.BAN else Membership.LEAVE
                roomSyncHandler.markRemovedWithoutEvent(stores, roomId, membership, aggregator)
            }
        }
        aggregatorHandler.handle(aggregator)
        return true
    }
}
