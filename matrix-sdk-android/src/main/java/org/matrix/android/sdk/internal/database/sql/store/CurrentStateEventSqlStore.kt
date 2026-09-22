/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.api.session.events.model.EventType
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

    /** State key to event id for a whole state type, without resolving each root event. */
    fun getEventIdsByStateKey(roomId: String, type: String): Map<String, String> =
            queries.selectEventIdsByRoomAndType(roomId, type) { stateKey, eventId -> stateKey to eventId }
                    .executeAsList()
                    .toMap()

    fun getOne(roomId: String, type: String, stateKey: String): CurrentStateEventEntity? =
            queries.selectOne(roomId, type, stateKey).executeAsOneOrNull()?.toEntity()

    fun upsert(roomId: String, type: String, stateKey: String, eventId: String, rootEventId: String?) =
            queries.upsert(roomId, type, stateKey, eventId, rootEventId)

    /**
     * MSC4362: an encrypted state event is held under its packed key until it can be decrypted, then
     * moves to its real (type, state key) — where it wins over any unencrypted event holding it.
     */
    fun applyDecryptedState(roomId: String, packedStateKey: String?, type: String, stateKey: String, eventId: String) {
        packedStateKey?.let { deleteOne(roomId, EventType.ENCRYPTED, it) }
        upsert(roomId, type, stateKey, eventId, eventId)
    }

    /** True while an encrypted state event holds this key, which an unencrypted one must not take. */
    fun isHeldByEncryptedState(roomId: String, type: String, stateKey: String): Boolean {
        val root = getOne(roomId, type, stateKey)?.root ?: return false
        return root.decryptionResultJson != null || root.type == EventType.ENCRYPTED
    }

    fun deleteOne(roomId: String, type: String, stateKey: String) = queries.deleteOne(roomId, type, stateKey)

    fun deleteByRoom(roomId: String) = queries.deleteByRoom(roomId)

    private fun CurrentStateEventRow.toEntity(): CurrentStateEventEntity = CurrentStateEventEntity(
            eventId = event_id,
            root = root_event_id?.let { eventStore.getByEventIdInRoom(room_id, it) },
            roomId = room_id,
            type = type,
            stateKey = state_key,
    )
}
