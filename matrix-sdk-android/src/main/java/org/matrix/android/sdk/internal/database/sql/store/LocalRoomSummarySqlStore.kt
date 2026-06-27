/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.api.session.room.model.LocalRoomCreationState
import org.matrix.android.sdk.api.session.room.model.create.CreateRoomParams
import org.matrix.android.sdk.api.session.room.model.create.toJSONString
import org.matrix.android.sdk.internal.database.model.LocalRoomSummaryEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.Local_room_summary as LocalRoomSummaryRow

/** SQL access for `local_room_summary`, resolving the linked room_summary by id. */
internal class LocalRoomSummarySqlStore(
        private val database: SessionSqlDatabase,
        private val roomSummaryStore: RoomSummarySqlStore,
) {

    private val queries get() = database.localRoomSummaryQueries

    fun get(roomId: String): LocalRoomSummaryEntity? = queries.selectByRoomId(roomId).executeAsOneOrNull()?.toEntity()

    fun upsert(entity: LocalRoomSummaryEntity) = queries.upsert(
            room_id = entity.roomId,
            room_summary_room_id = entity.roomSummaryEntity?.roomId,
            replacement_room_id = entity.replacementRoomId,
            state_str = entity.creationState.name,
            create_room_params_str = entity.createRoomParams?.toJSONString(),
    )

    fun delete(roomId: String) = queries.deleteByRoomId(roomId)

    private fun LocalRoomSummaryRow.toEntity(): LocalRoomSummaryEntity = LocalRoomSummaryEntity(
            roomId = room_id,
            roomSummaryEntity = room_summary_room_id?.let { roomSummaryStore.get(it) },
            replacementRoomId = replacement_room_id,
    ).also {
        it.creationState = LocalRoomCreationState.valueOf(state_str)
        it.createRoomParams = CreateRoomParams.fromJson(create_room_params_str)
    }
}
