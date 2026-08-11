/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.summary

import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.WatchedRoomInfo
import org.matrix.android.sdk.internal.database.model.RoomSummaryEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.room.membership.RoomName
import org.matrix.android.sdk.internal.task.Task
import org.matrix.android.sdk.internal.util.Normalizer
import org.matrix.android.sdk.internal.util.time.Clock
import javax.inject.Inject

internal interface SyncWatchedRoomSummariesTask : Task<SyncWatchedRoomSummariesTask.Params, Unit> {
    data class Params(
            val rooms: List<WatchedRoomInfo>,
    )
}

/**
 * Materialize the watched-rooms registry (synced account data) as membership-NONE summary rows, so
 * the Watching list can be a plain summary query. Rooms with real local membership are left alone;
 * un-watched never-joined rows are deleted outright.
 */
internal class DefaultSyncWatchedRoomSummariesTask @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val sessionDbDispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val normalizer: Normalizer,
        private val clock: Clock,
) : SyncWatchedRoomSummariesTask {

    override suspend fun execute(params: SyncWatchedRoomSummariesTask.Params) {
        val wantedIds = params.rooms.map { it.roomId }.toSet()
        database.awaitDbTransaction(sessionDbDispatcher) {
            stores.roomSummary.getWatchedRoomIds()
                    .filter { it !in wantedIds }
                    .forEach { roomId ->
                        val entity = stores.roomSummary.get(roomId) ?: return@forEach
                        if (entity.membership == Membership.NONE) {
                            stores.roomSummary.delete(roomId)
                        } else {
                            entity.isWatched = false
                            stores.roomSummary.upsert(entity)
                        }
                    }
            params.rooms.forEach { info ->
                val entity = stores.roomSummary.get(info.roomId) ?: RoomSummaryEntity(roomId = info.roomId)
                if (entity.membership != Membership.NONE) return@forEach
                val displayName = info.name ?: info.alias ?: info.roomId
                entity.setDisplayName(RoomName(displayName, normalizer.normalize(displayName)))
                entity.name = info.name.orEmpty()
                entity.avatarUrl = info.avatarUrl
                entity.topic = info.topic
                entity.canonicalAlias = info.alias
                if (entity.lastActivityTime == null) entity.lastActivityTime = clock.epochMillis()
                entity.isWatched = true
                stores.roomSummary.upsert(entity)
            }
        }
    }
}
