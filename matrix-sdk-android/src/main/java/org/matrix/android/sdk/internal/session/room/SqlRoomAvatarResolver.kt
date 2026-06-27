/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room

import org.matrix.android.sdk.api.MatrixConfiguration
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomAvatarContent
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.room.membership.SqlRoomMemberHelper
import javax.inject.Inject

/** SQLDelight counterpart of [RoomAvatarResolver]. */
internal class SqlRoomAvatarResolver @Inject constructor(
        matrixConfiguration: MatrixConfiguration,
        @UserId private val userId: String,
) {

    private val roomDisplayNameFallbackProvider = matrixConfiguration.roomDisplayNameFallbackProvider

    fun resolve(stores: SessionStores, roomId: String): String? {
        val roomAvatarUrl = stores.currentStateEvent.getOne(roomId, EventType.STATE_ROOM_AVATAR, "")
                ?.root
                ?.asDomain()
                ?.content
                ?.toModel<RoomAvatarContent>()
                ?.avatarUrl
        if (!roomAvatarUrl.isNullOrEmpty()) {
            return roomAvatarUrl
        }

        val summary = stores.database.roomSummaryQueries.selectByRoomId(roomId).executeAsOneOrNull()
        if (roomDisplayNameFallbackProvider.shouldOverrideDirectChatDisplay()) {
            val directUserId = summary?.direct_user_id
            if (!directUserId.isNullOrBlank()) {
                val directAvatarUrl = SqlRoomMemberHelper(stores, roomId).getLastRoomMember(directUserId)?.avatarUrl
                if (!directAvatarUrl.isNullOrBlank()) {
                    return directAvatarUrl
                }
            }
        }

        val isDirectRoom = summary?.is_direct == 1L
        if (isDirectRoom) {
            val excludedUserIds = roomDisplayNameFallbackProvider.excludedUserIds(roomId)
            val roomMembers = SqlRoomMemberHelper(stores, roomId)
            val members = roomMembers.queryActiveRoomMembersEvent().filterNot { it.userId in excludedUserIds }

            if (members.size == 1) {
                val firstLeftAvatarUrl = stores.roomMember.getByRoomAndMemberships(roomId, listOf(Membership.LEAVE))
                        .firstOrNull { !it.avatarUrl.isNullOrEmpty() }
                        ?.avatarUrl
                return firstLeftAvatarUrl ?: members.firstOrNull()?.avatarUrl
            } else if (members.size == 2) {
                return members.firstOrNull { it.userId != userId }?.avatarUrl
            }
        }

        return null
    }
}
