/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.internal.database.model.RoomEntity
import org.matrix.android.sdk.internal.database.model.RoomMembersLoadStatusType
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.Room as RoomRow

/** SQL access for the `room` table. The room's RealmLists (chunks, sending events, threads, account
 * data) are owned by their own stores keyed by room_id. */
internal class RoomSqlStore(private val database: SessionSqlDatabase) {

    private val queries get() = database.roomQueries

    fun get(roomId: String): RoomEntity? = queries.selectByRoomId(roomId).executeAsOneOrNull()?.toEntity()

    fun getAll(): List<RoomEntity> = queries.selectAll().executeAsList().map { it.toEntity() }

    fun getByMemberships(memberships: List<Membership>): List<RoomEntity> =
            queries.selectByMembership(memberships.map { it.name }).executeAsList().map { it.toEntity() }

    fun upsert(entity: RoomEntity) = queries.upsert(entity.roomId, entity.membership.name, entity.membersLoadStatus.name)

    fun updateMembership(roomId: String, membership: Membership) = queries.updateMembership(membership.name, roomId)

    fun updateMembersLoadStatus(roomId: String, status: RoomMembersLoadStatusType) =
            queries.updateMembersLoadStatus(status.name, roomId)

    fun delete(roomId: String) = queries.deleteByRoomId(roomId)

    private fun RoomRow.toEntity(): RoomEntity = RoomEntity(roomId = room_id).also {
        it.membership = Membership.valueOf(membership_str)
        it.membersLoadStatus = RoomMembersLoadStatusType.valueOf(members_load_status_str)
    }
}
