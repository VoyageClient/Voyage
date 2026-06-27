/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.internal.database.model.CurrentStateEventEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.Current_state_event as CurrentStateEventRow

/** SQL access for `current_state_event`, resolving the root EventEntity by db id. */
internal class CurrentStateEventSqlStore(
        private val database: SessionSqlDatabase,
        private val eventStore: EventSqlStore,
) {

    private val queries get() = database.currentStateEventQueries

    fun getByRoom(roomId: String): List<CurrentStateEventEntity> = queries.selectByRoom(roomId).executeAsList().map { it.toEntity() }

    fun getByRoomAndType(roomId: String, type: String): List<CurrentStateEventEntity> =
            queries.selectByRoomAndType(roomId, type).executeAsList().map { it.toEntity() }

    fun getOne(roomId: String, type: String, stateKey: String): CurrentStateEventEntity? =
            queries.selectOne(roomId, type, stateKey).executeAsOneOrNull()?.toEntity()

    fun upsert(roomId: String, type: String, stateKey: String, eventId: String, rootEventId: String?) =
            queries.upsert(roomId, type, stateKey, eventId, rootEventId)

    fun deleteByRoom(roomId: String) = queries.deleteByRoom(roomId)

    private fun CurrentStateEventRow.toEntity(): CurrentStateEventEntity = CurrentStateEventEntity(
            eventId = event_id,
            root = root_event_id?.let { eventStore.getByEventIdInRoom(room_id, it) },
            roomId = room_id,
            type = type,
            stateKey = state_key,
    )
}
