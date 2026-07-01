/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.membership

import org.matrix.android.sdk.api.MatrixConfiguration
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomCanonicalAliasContent
import org.matrix.android.sdk.api.session.room.model.RoomNameContent
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.model.RoomMemberSummaryEntity
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sql.store.splitToList
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.displayname.DisplayNameResolver
import org.matrix.android.sdk.internal.util.Normalizer
import javax.inject.Inject

/** SQLDelight counterpart of [RoomDisplayNameResolver]. */
internal class SqlRoomDisplayNameResolver @Inject constructor(
        matrixConfiguration: MatrixConfiguration,
        private val displayNameResolver: DisplayNameResolver,
        private val normalizer: Normalizer,
        @UserId private val userId: String,
) {
    private val roomDisplayNameFallbackProvider = matrixConfiguration.roomDisplayNameFallbackProvider

    fun resolve(stores: SessionStores, roomId: String): RoomName {
        var name: String?
        val roomMembership = stores.room.get(roomId)?.membership
        val summary = stores.database.roomSummaryQueries.selectByRoomId(roomId).executeAsOneOrNull()

        val roomName = stores.currentStateEvent.getOne(roomId, EventType.STATE_ROOM_NAME, "")?.root
        name = ContentMapper.map(roomName?.content).toModel<RoomNameContent>()?.name
        if (!name.isNullOrEmpty()) return name.toRoomName()

        val canonicalAlias = stores.currentStateEvent.getOne(roomId, EventType.STATE_ROOM_CANONICAL_ALIAS, "")?.root
        name = ContentMapper.map(canonicalAlias?.content).toModel<RoomCanonicalAliasContent>()?.canonicalAlias
        if (!name.isNullOrEmpty()) return name.toRoomName()

        val roomMembers = SqlRoomMemberHelper(stores, roomId)
        if (roomDisplayNameFallbackProvider.shouldOverrideDirectChatDisplay()) {
            val directUserId = summary?.direct_user_id
            if (!directUserId.isNullOrBlank()) {
                val directMember = roomMembers.getLastRoomMember(directUserId)
                // Only force the DM target's name while they are still in the room; once they leave we
                // fall through to the normal multi-member naming instead of clinging to a stale name.
                if (directMember?.membership?.isActive() == true && !directMember.displayName.isNullOrBlank()) {
                    return resolveRoomMemberName(directMember, roomMembers).toRoomName()
                }
            }
        }

        val activeMembers = roomMembers.queryActiveRoomMembersEvent()
        if (roomMembership == Membership.INVITE) {
            val inviterId = roomMembers.getLastStateEvent(userId)?.sender
            name = inviterId
                    ?.let { id ->
                        activeMembers.firstOrNull { it.userId == id }
                                ?.toMatrixItem()
                                ?.let { displayNameResolver.getBestName(it) }
                    }
                    ?: roomDisplayNameFallbackProvider.getNameForRoomInvite()
        } else if (roomMembership == Membership.JOIN) {
            val excludedUserIds = roomDisplayNameFallbackProvider.excludedUserIds(roomId)
            val heroes = summary?.heroes.splitToList()
            val invitedCount = summary?.invited_members_count?.toInt() ?: 0
            val joinedCount = summary?.joined_members_count?.toInt() ?: 0
            val otherMembersSubset: List<RoomMemberSummaryEntity> = if (heroes.isNotEmpty()) {
                heroes.mapNotNull { heroId ->
                    roomMembers.getLastRoomMember(heroId)?.takeIf {
                        (it.membership == Membership.INVITE || it.membership == Membership.JOIN) && !excludedUserIds.contains(it.userId)
                    }
                }
            } else {
                activeMembers.filter { it.userId != userId && it.userId !in excludedUserIds }.take(5)
            }
            val otherMembersCount = otherMembersSubset.count()
            name = when (otherMembersCount) {
                0 -> {
                    val leftMembersNames = stores.roomMember.getByRoomAndMemberships(roomId, listOf(Membership.LEAVE))
                            .filterNot { it.userId in excludedUserIds }
                            .map { displayNameResolver.getBestName(it.toMatrixItem()) }
                    val directUserId = summary?.direct_user_id
                    if (!directUserId.isNullOrBlank() && leftMembersNames.isEmpty()) {
                        directUserId
                    } else {
                        roomDisplayNameFallbackProvider.getNameForEmptyRoom(summary?.is_direct == 1L, leftMembersNames)
                    }
                }
                1 -> roomDisplayNameFallbackProvider.getNameFor1member(resolveRoomMemberName(otherMembersSubset[0], roomMembers))
                2 -> roomDisplayNameFallbackProvider.getNameFor2members(
                        resolveRoomMemberName(otherMembersSubset[0], roomMembers),
                        resolveRoomMemberName(otherMembersSubset[1], roomMembers),
                )
                3 -> roomDisplayNameFallbackProvider.getNameFor3members(
                        resolveRoomMemberName(otherMembersSubset[0], roomMembers),
                        resolveRoomMemberName(otherMembersSubset[1], roomMembers),
                        resolveRoomMemberName(otherMembersSubset[2], roomMembers),
                )
                4 -> roomDisplayNameFallbackProvider.getNameFor4members(
                        resolveRoomMemberName(otherMembersSubset[0], roomMembers),
                        resolveRoomMemberName(otherMembersSubset[1], roomMembers),
                        resolveRoomMemberName(otherMembersSubset[2], roomMembers),
                        resolveRoomMemberName(otherMembersSubset[3], roomMembers),
                )
                else -> {
                    val remainingCount = invitedCount + joinedCount - otherMembersCount + 1
                    roomDisplayNameFallbackProvider.getNameFor4membersAndMore(
                            resolveRoomMemberName(otherMembersSubset[0], roomMembers),
                            resolveRoomMemberName(otherMembersSubset[1], roomMembers),
                            resolveRoomMemberName(otherMembersSubset[2], roomMembers),
                            remainingCount,
                    )
                }
            }
        }
        return (name ?: roomId).toRoomName()
    }

    private fun resolveRoomMemberName(roomMemberSummary: RoomMemberSummaryEntity, roomMemberHelper: SqlRoomMemberHelper): String {
        val isUnique = roomMemberHelper.isUniqueDisplayName(roomMemberSummary.displayName)
        return if (isUnique) {
            displayNameResolver.getBestName(roomMemberSummary.toMatrixItem())
        } else {
            "${roomMemberSummary.displayName} (${roomMemberSummary.userId})"
        }
    }

    private fun String.toRoomName() = RoomName(this, normalizedName = normalizer.normalize(this))
}
