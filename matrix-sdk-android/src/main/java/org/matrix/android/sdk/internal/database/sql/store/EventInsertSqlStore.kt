/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.internal.database.model.EventInsertEntity
import org.matrix.android.sdk.internal.database.model.EventInsertType
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.Event_insert as EventInsertRow

/** SQL access for `event_insert` (the live-processing queue). */
internal class EventInsertSqlStore(private val database: SessionSqlDatabase) {

    private val queries get() = database.eventInsertQueries

    fun getAll(): List<EventInsertEntity> = queries.selectAll().executeAsList().map { it.toEntity() }

    fun insert(eventId: String, eventType: String, canBeProcessed: Boolean, insertType: EventInsertType) =
            queries.insert(eventId, eventType, if (canBeProcessed) 1L else 0L, insertType.name)

    fun setCanBeProcessed(eventId: String, canBeProcessed: Boolean) =
            queries.setCanBeProcessed(if (canBeProcessed) 1L else 0L, eventId)

    fun deleteByEventId(eventId: String) = queries.deleteByEventId(eventId)

    fun deleteAll() = queries.deleteAll()

    private fun EventInsertRow.toEntity(): EventInsertEntity = EventInsertEntity(
            eventId = event_id,
            eventType = event_type,
            canBeProcessed = can_be_processed != 0L,
    ).also {
        it.insertType = EventInsertType.valueOf(insert_type_str)
    }
}
