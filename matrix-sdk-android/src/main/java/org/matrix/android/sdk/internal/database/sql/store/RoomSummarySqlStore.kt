/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.VersioningState
import org.matrix.android.sdk.api.session.room.model.tag.RoomTag
import org.matrix.android.sdk.internal.database.model.RoomSummaryEntity
import org.matrix.android.sdk.internal.database.model.RoomTagEntity
import org.matrix.android.sdk.internal.database.model.SpaceChildSummaryEntity
import org.matrix.android.sdk.internal.database.model.SpaceParentSummaryEntity
import org.matrix.android.sdk.internal.database.model.UserDraftsEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.session.room.membership.RoomName
import org.matrix.android.sdk.internal.database.sql.Room_summary as RoomSummaryRow

/**
 * SQL access for `room_summary` plus its owned lists: tags ([RoomTagSqlStore]), drafts
 * ([DraftSqlStore]) and the space parent/child summaries ([SpaceSqlStore]). The nested
 * parent/child RoomSummaryEntity refs are resolved recursively with an ancestor guard against cycles.
 */
internal class RoomSummarySqlStore(
        private val database: SessionSqlDatabase,
        private val spaceStore: SpaceSqlStore,
        private val roomTagStore: RoomTagSqlStore,
        private val draftStore: DraftSqlStore,
        private val timelineEventStore: TimelineEventSqlStore,
        private val userStore: UserSqlStore,
) {

    private val queries get() = database.roomSummaryQueries

    fun get(roomId: String): RoomSummaryEntity? = getEntity(roomId, emptySet())

    fun getAll(): List<RoomSummaryEntity> = queries.selectAll().executeAsList().map { it.toEntity(emptySet()) }

    fun updateEncryptionTrustLevel(roomId: String, level: String?) = queries.updateEncryptionTrustLevel(level, roomId)

    fun updateDirectInfo(roomId: String, isDirect: Boolean, directUserId: String?) =
            queries.updateDirectInfo(if (isDirect) 1L else 0L, directUserId, roomId)

    fun updateLastActivityTime(roomId: String, time: Long?) = queries.updateLastActivityTime(time, roomId)

    /** (roomId, directUserId, membershipStr) for every direct room. */
    fun directRooms(): List<Triple<String, String?, String>> =
            queries.selectDirectRooms().executeAsList().map { Triple(it.room_id, it.direct_user_id, it.membership_str) }

    /** Rooms we're still in (join/invite) that have any of [userIds] as another active member. */
    fun roomIdsWithActiveMembers(userIds: Collection<String>): List<String> {
        if (userIds.isEmpty()) return emptyList()
        val active = Membership.activeMemberships().map { it.name }.toSet()
        return queries.selectRoomMembershipInfo().executeAsList()
                .filter { it.membership_str in active && it.other_member_ids.splitToList().any { id -> id in userIds } }
                .map { it.room_id }
    }

    /** Rooms whose current room-list preview event was authored by one of [senders]. */
    fun roomIdsWithPreviewFromSenders(senders: Collection<String>): List<String> {
        if (senders.isEmpty()) return emptyList()
        return queries.selectRoomIdsWithPreviewFromSenders(senders).executeAsList()
    }

    /** Invite rooms whose inviter is one of [inviters]. */
    fun inviteRoomIdsByInviters(inviters: Collection<String>): List<String> {
        if (inviters.isEmpty()) return emptyList()
        return queries.selectInviteRoomIdsByInviters(Membership.INVITE.name, inviters).executeAsList()
    }

    fun setHiddenFromUser(roomId: String, hidden: Boolean) = queries.updateHiddenFromUser(hidden.toLong(), roomId)

    // Guarded: the FK never changes once linked, but SQLDelight notifies room_summary listeners on any
    // executed UPDATE — an unguarded link made every presence event invalidate the whole room list.
    fun linkDirectUserPresence(userId: String) {
        if (queries.countDirectRoomsMissingPresenceLink(userId, userId).executeAsOne() == 0L) return
        queries.updateDirectUserPresence(userId, userId)
    }

    fun ensureExists(roomId: String) = queries.insertEmptyIfAbsent(roomId)

    /** Rooms whose latest previewable event is one of [eventIds] (used after those events decrypt). */
    fun roomIdsWithPreviewEvent(eventIds: Collection<String>): List<String> =
            eventIds.flatMapInChunks { queries.selectRoomIdsByLatestPreviewEvents(it).executeAsList() }

    fun getEncryptedRoomIds(membership: Membership): List<String> =
            queries.selectEncryptedRoomIdsByMembership(membership.name).executeAsList()

    fun getRoomIdsByMembership(membership: Membership): List<String> =
            queries.selectRoomIdsByMembership(membership.name).executeAsList()

    fun isEncrypted(roomId: String): Boolean =
            queries.selectIsEncrypted(roomId).executeAsOneOrNull() == 1L

    /** No-op write that makes room_summary listeners re-emit for [roomId]. */
    fun touch(roomId: String) = queries.touchByRoomId(roomId)

    fun updateTags(roomId: String, tags: List<Pair<String, Double?>>) {
        database.transaction {
            roomTagStore.replaceTags(roomId, tags.map { RoomTagEntity(it.first, it.second) })
            queries.insertEmptyIfAbsent(roomId)
            queries.updateTagFlags(
                    is_favourite = if (tags.any { it.first == RoomTag.ROOM_TAG_FAVOURITE }) 1L else 0L,
                    is_low_priority = if (tags.any { it.first == RoomTag.ROOM_TAG_LOW_PRIORITY }) 1L else 0L,
                    is_server_notice = if (tags.any { it.first == RoomTag.ROOM_TAG_SERVER_NOTICE }) 1L else 0L,
                    room_id = roomId,
            )
        }
    }

    fun updateReadMarkerId(roomId: String, eventId: String?) {
        queries.insertEmptyIfAbsent(roomId)
        queries.updateReadMarkerId(eventId, roomId)
    }

    fun updateMarkedUnread(roomId: String, markedUnread: Boolean) {
        queries.insertEmptyIfAbsent(roomId)
        queries.updateMarkedUnread(if (markedUnread) 1L else 0L, roomId)
    }

    fun clearUnreadCounters(roomId: String) = queries.clearUnreadCounters(roomId)

    fun resetBreadcrumbsIndex() = queries.resetBreadcrumbsIndex()

    fun updateBreadcrumbsIndex(roomId: String, index: Int) = queries.updateBreadcrumbsIndex(index.toLong(), roomId)

    fun delete(roomId: String) {
        roomTagStore.deleteTags(roomId)
        draftStore.deleteDrafts(roomId)
        spaceStore.deleteChildren(roomId)
        spaceStore.deleteParents(roomId)
        queries.deleteByRoomId(roomId)
    }

    fun upsert(entity: RoomSummaryEntity) {
        // Tags have exactly one writer, [updateTags], fed by m.tag account data. A summary write must
        // never be able to state them: most callers build the entity from a bare RoomSummaryEntity(roomId)
        // when no row exists yet, and persisting that entity's empty tag set would silently unfavourite
        // the room until the server next happens to resend m.tag.
        val tagFlags = queries.selectTagFlags(entity.roomId).executeAsOneOrNull()
        queries.upsert(
                room_id = entity.roomId,
                room_type = entity.roomType,
                display_name = entity.displayName(),
                normalized_display_name = entity.normalizedDisplayName(),
                avatar_url = entity.avatarUrl,
                name = entity.name,
                topic = entity.topic,
                topic_formatted = entity.topicFormatted,
                latest_previewable_event_id = entity.latestPreviewableEvent?.eventId,
                last_activity_time = entity.lastActivityTime,
                heroes = entity.heroes.toList().joinToColumn(),
                joined_members_count = entity.joinedMembersCount?.toLong(),
                invited_members_count = entity.invitedMembersCount?.toLong(),
                is_direct = entity.isDirect.toLong(),
                direct_user_id = entity.directUserId,
                other_member_ids = entity.otherMemberIds.toList().joinToColumn(),
                notification_count = entity.notificationCount.toLong(),
                highlight_count = entity.highlightCount.toLong(),
                thread_notification_count = entity.threadNotificationCount.toLong(),
                thread_highlight_count = entity.threadHighlightCount.toLong(),
                read_marker_id = entity.readMarkerId,
                has_unread_messages = entity.hasUnreadMessages.toLong(),
                marked_unread = entity.markedUnread.toLong(),
                is_favourite = tagFlags?.is_favourite ?: 0L,
                is_low_priority = tagFlags?.is_low_priority ?: 0L,
                is_server_notice = tagFlags?.is_server_notice ?: 0L,
                breadcrumbs_index = entity.breadcrumbsIndex.toLong(),
                canonical_alias = entity.canonicalAlias,
                aliases = entity.aliases.toList().joinToColumn(),
                flat_aliases = entity.flatAliases,
                is_encrypted = entity.isEncrypted.toLong(),
                e2e_algorithm = entity.e2eAlgorithm,
                encryption_event_ts = entity.encryptionEventTs,
                room_encryption_trust_level_str = entity.roomEncryptionTrustLevel?.name,
                inviter_id = entity.inviterId,
                direct_user_presence_user_id = entity.directUserPresence?.userId,
                has_failed_sending = entity.hasFailedSending.toLong(),
                flatten_parent_ids = entity.flattenParentIds,
                membership_str = entity.membership.name,
                is_hidden_from_user = entity.isHiddenFromUser.toLong(),
                versioning_state_str = entity.versioningState.name,
                join_rules_str = entity.joinRules?.name,
                direct_parent_names = entity.directParentNames.toList().joinToColumn(),
        )
        draftStore.replaceDrafts(entity.roomId, entity.userDrafts?.userDrafts?.toList().orEmpty())
        spaceStore.replaceChildren(entity.roomId, entity.children.map {
            SpaceSqlStore.SpaceChildInsert(
                    order = it.order,
                    autoJoin = it.autoJoin?.toLong(),
                    suggested = it.suggested?.toLong(),
                    childRoomId = it.childRoomId,
                    childSummaryRoomId = it.childSummaryEntity?.roomId,
                    viaServers = it.viaServers.toList().joinToColumn(),
            )
        })
        spaceStore.replaceParents(entity.roomId, entity.parents.map {
            SpaceSqlStore.SpaceParentInsert(
                    canonical = it.canonical?.toLong(),
                    parentRoomId = it.parentRoomId,
                    parentSummaryRoomId = it.parentSummaryEntity?.roomId,
                    viaServers = it.viaServers.toList().joinToColumn(),
            )
        })
    }

    private fun getEntity(roomId: String, ancestors: Set<String>): RoomSummaryEntity? =
            queries.selectByRoomId(roomId).executeAsOneOrNull()?.toEntity(ancestors)

    private fun RoomSummaryRow.toEntity(ancestors: Set<String>): RoomSummaryEntity {
        val nextAncestors = ancestors + room_id
        val parents = spaceStore.parentRows(room_id).map { p ->
            SpaceParentSummaryEntity(
                    canonical = p.canonical?.let { it != 0L },
                    parentRoomId = p.parent_room_id,
                    parentSummaryEntity = p.parent_summary_room_id
                            ?.takeUnless { it in nextAncestors }
                            ?.let { getEntity(it, nextAncestors) },
                    viaServers = p.via_servers.splitToRealmList(),
            )
        }
        val children = spaceStore.childRows(room_id).map { c ->
            SpaceChildSummaryEntity(
                    order = c.child_order,
                    autoJoin = c.auto_join?.let { it != 0L },
                    suggested = c.suggested?.let { it != 0L },
                    childRoomId = c.child_room_id,
                    childSummaryEntity = c.child_summary_room_id
                            ?.takeUnless { it in nextAncestors }
                            ?.let { getEntity(it, nextAncestors) },
                    viaServers = c.via_servers.splitToRealmList(),
            )
        }
        val entity = RoomSummaryEntity(
                roomId = room_id,
                roomType = room_type,
                parents = ArrayList<SpaceParentSummaryEntity>().apply { addAll(parents) },
                children = ArrayList<SpaceChildSummaryEntity>().apply { addAll(children) },
                directParentNames = direct_parent_names.splitToRealmList(),
        )
        entity.setDisplayName(RoomName(display_name ?: "", normalized_display_name ?: ""))
        entity.avatarUrl = avatar_url
        entity.name = name
        entity.topic = topic
        entity.topicFormatted = topic_formatted
        entity.latestPreviewableEvent = latest_previewable_event_id?.let { timelineEventStore.getByRoomAndEventId(room_id, it) }
        entity.lastActivityTime = last_activity_time
        entity.heroes = heroes.splitToRealmList()
        entity.joinedMembersCount = joined_members_count?.toInt()
        entity.invitedMembersCount = invited_members_count?.toInt()
        entity.isDirect = is_direct != 0L
        entity.directUserId = direct_user_id
        entity.otherMemberIds = other_member_ids.splitToRealmList()
        entity.notificationCount = notification_count.toInt()
        entity.highlightCount = highlight_count.toInt()
        entity.threadNotificationCount = thread_notification_count.toInt()
        entity.threadHighlightCount = thread_highlight_count.toInt()
        entity.readMarkerId = read_marker_id
        entity.hasUnreadMessages = has_unread_messages != 0L
        entity.markedUnread = marked_unread != 0L
        entity.updateTags(roomTagStore.getTags(room_id).map { it.tagName to it.tagOrder })
        entity.breadcrumbsIndex = breadcrumbs_index.toInt()
        entity.canonicalAlias = canonical_alias
        entity.updateAliases(aliases.splitToList())
        entity.isEncrypted = is_encrypted != 0L
        entity.e2eAlgorithm = e2e_algorithm
        entity.encryptionEventTs = encryption_event_ts
        entity.roomEncryptionTrustLevelStr = room_encryption_trust_level_str
        entity.inviterId = inviter_id
        entity.directUserPresence = direct_user_presence_user_id?.let { userStore.getPresence(it) }
        entity.hasFailedSending = has_failed_sending != 0L
        entity.flattenParentIds = flatten_parent_ids
        entity.membership = Membership.valueOf(membership_str)
        entity.isHiddenFromUser = is_hidden_from_user != 0L
        entity.versioningState = VersioningState.valueOf(versioning_state_str)
        entity.joinRules = join_rules_str?.let { runCatching { org.matrix.android.sdk.api.session.room.model.RoomJoinRules.valueOf(it) }.getOrNull() }
        val drafts = draftStore.getDrafts(room_id)
        if (drafts.isNotEmpty()) {
            entity.userDrafts = UserDraftsEntity(ArrayList<org.matrix.android.sdk.internal.database.model.DraftEntity>().apply { addAll(drafts) })
        }
        return entity
    }

    private fun Boolean.toLong(): Long = if (this) 1L else 0L
}
