/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.membership

import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.RoomMemberSummaryEntity
import org.matrix.android.sdk.internal.database.sql.store.SessionStores

/** SQLDelight counterpart of [RoomMemberHelper] (read helper around STATE_ROOM_MEMBER membership). */
internal class SqlRoomMemberHelper(
        private val stores: SessionStores,
        private val roomId: String,
) {

    private val joinedMembersCount: Int? by lazy {
        stores.database.roomSummaryQueries.selectByRoomId(roomId).executeAsOneOrNull()?.joined_members_count?.toInt()
    }
    private val invitedMembersCount: Int? by lazy {
        stores.database.roomSummaryQueries.selectByRoomId(roomId).executeAsOneOrNull()?.invited_members_count?.toInt()
    }

    fun getLastStateEvent(userId: String): EventEntity? =
            stores.currentStateEvent.getOne(roomId, EventType.STATE_ROOM_MEMBER, userId)?.root

    fun getLastRoomMember(userId: String): RoomMemberSummaryEntity? = stores.roomMember.getByRoomAndUser(roomId, userId)

    fun isUniqueDisplayName(displayName: String?): Boolean {
        if (displayName.isNullOrEmpty()) return true
        return stores.roomMember.getByRoom(roomId).count { it.displayName == displayName } == 1
    }

    fun queryRoomMembersEvent(): List<RoomMemberSummaryEntity> = stores.roomMember.getByRoom(roomId)

    fun queryJoinedRoomMembersEvent(): List<RoomMemberSummaryEntity> =
            stores.roomMember.getByRoomAndMemberships(roomId, listOf(Membership.JOIN))

    fun queryInvitedRoomMembersEvent(): List<RoomMemberSummaryEntity> =
            stores.roomMember.getByRoomAndMemberships(roomId, listOf(Membership.INVITE))

    fun queryActiveRoomMembersEvent(): List<RoomMemberSummaryEntity> =
            stores.roomMember.getByRoomAndMemberships(roomId, listOf(Membership.INVITE, Membership.JOIN))

    fun getNumberOfJoinedMembers(): Int = joinedMembersCount ?: queryJoinedRoomMembersEvent().size

    fun getNumberOfInvitedMembers(): Int = invitedMembersCount ?: queryInvitedRoomMembersEvent().size

    fun getNumberOfMembers(): Int = getNumberOfJoinedMembers() + getNumberOfInvitedMembers()

    fun getActiveRoomMemberIds(): List<String> = queryActiveRoomMembersEvent().map { it.userId }

    fun getJoinedRoomMemberIds(): List<String> = queryJoinedRoomMembersEvent().map { it.userId }
}
