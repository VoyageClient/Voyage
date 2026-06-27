/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.internal.database.model.RoomTagEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.Room_tag as RoomTagRow

/** SQL access for `room_tag` (embedded under a room). */
internal class RoomTagSqlStore(private val database: SessionSqlDatabase) {

    private val queries get() = database.roomTagQueries

    fun getTags(roomId: String): List<RoomTagEntity> = queries.selectByRoom(roomId).executeAsList().map { it.toEntity() }

    fun replaceTags(roomId: String, tags: List<RoomTagEntity>) {
        queries.deleteByRoom(roomId)
        tags.forEach { queries.upsert(roomId, it.tagName, it.tagOrder) }
    }

    fun deleteTags(roomId: String) = queries.deleteByRoom(roomId)

    private fun RoomTagRow.toEntity(): RoomTagEntity = RoomTagEntity(
            tagName = tag_name,
            tagOrder = tag_order,
    )
}
