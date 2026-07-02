/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.internal.database.model.RoomMemberSummaryEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.Room_member_summary as RoomMemberSummaryRow

/** SQL access for `room_member_summary`, resolving the optional user_presence FK. */
internal class RoomMemberSqlStore(private val database: SessionSqlDatabase) {

    private val queries get() = database.roomMemberSummaryQueries
    private val presenceQueries get() = database.userPresenceQueries

    fun get(primaryKey: String): RoomMemberSummaryEntity? = queries.selectByPrimaryKey(primaryKey).executeAsOneOrNull()?.toEntity()

    fun getByRoom(roomId: String): List<RoomMemberSummaryEntity> = queries.selectByRoom(roomId).executeAsList().map { it.toEntity() }

    fun getByRoomAndMemberships(roomId: String, memberships: List<Membership>): List<RoomMemberSummaryEntity> =
            queries.selectByRoomAndMembership(roomId, memberships.map { it.name }).executeAsList().map { it.toEntity() }

    fun getByRoomAndUser(roomId: String, userId: String): RoomMemberSummaryEntity? =
            queries.selectByRoomAndUser(roomId, userId).executeAsOneOrNull()?.toEntity()

    fun upsert(entity: RoomMemberSummaryEntity) = queries.upsert(
            primary_key = entity.primaryKey,
            user_id = entity.userId,
            room_id = entity.roomId,
            display_name = entity.displayName,
            avatar_url = entity.avatarUrl,
            reason = entity.reason,
            is_direct = if (entity.isDirect) 1L else 0L,
            membership_str = entity.membership.name,
            user_presence_user_id = entity.userPresenceEntity?.userId,
    )

    // Guarded like RoomSummarySqlStore.linkDirectUserPresence: skip the (listener-notifying) UPDATE when
    // every member row already carries the presence link.
    fun linkUserPresence(userId: String) {
        if (queries.countMembersMissingPresenceLink(userId, userId).executeAsOne() == 0L) return
        queries.updateUserPresence(userId, userId)
    }

    fun deleteByRoom(roomId: String) = queries.deleteByRoom(roomId)

    private fun RoomMemberSummaryRow.toEntity(): RoomMemberSummaryEntity = RoomMemberSummaryEntity(
            primaryKey = primary_key,
            userId = user_id,
            roomId = room_id,
            displayName = display_name,
            avatarUrl = avatar_url,
            reason = reason,
            isDirect = is_direct != 0L,
    ).also {
        it.membership = Membership.valueOf(membership_str)
        it.userPresenceEntity = user_presence_user_id?.let { id -> presenceQueries.selectByUserId(id).executeAsOneOrNull()?.toEntity() }
    }
}
