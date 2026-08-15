/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.summary

import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.content.EncryptionEventContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.accountdata.RoomAccountDataTypes
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.PowerLevelsContent
import org.matrix.android.sdk.api.session.room.model.RoomCanonicalAliasContent
import org.matrix.android.sdk.api.session.room.model.RoomJoinRulesContent
import org.matrix.android.sdk.api.session.room.model.RoomNameContent
import org.matrix.android.sdk.api.session.room.model.RoomTopicContent
import org.matrix.android.sdk.api.session.room.model.RoomType
import org.matrix.android.sdk.api.session.room.model.create.RoomCreateContent
import org.matrix.android.sdk.api.session.room.model.create.RoomCreateContentWithSender
import org.matrix.android.sdk.api.session.room.powerlevels.RoomPowerLevels
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.sync.model.RoomSyncSummary
import org.matrix.android.sdk.api.session.sync.model.RoomSyncUnreadNotifications
import org.matrix.android.sdk.api.session.sync.model.RoomSyncUnreadThreadNotifications
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.model.RoomSummaryEntity
import org.matrix.android.sdk.internal.database.model.SpaceChildSummaryEntity
import org.matrix.android.sdk.internal.database.model.SpaceParentSummaryEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sql.store.isEventRead
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.room.SqlRoomAvatarResolver
import org.matrix.android.sdk.internal.session.room.accountdata.RoomAccountDataDataSource
import org.matrix.android.sdk.internal.session.room.membership.SqlRoomDisplayNameResolver
import org.matrix.android.sdk.internal.session.room.membership.SqlRoomMemberHelper
import org.matrix.android.sdk.internal.session.room.relationship.SqlRoomChildRelationInfo
import org.matrix.android.sdk.internal.session.room.timeline.RoomSummaryEventDecryptor
import org.matrix.android.sdk.internal.session.sync.SyncResponsePostTreatmentAggregator
import timber.log.Timber
import javax.inject.Inject

/** SQLDelight counterpart of [RoomSummaryUpdater]. Mutates an unmanaged [RoomSummaryEntity] then upserts it. */
internal class SqlRoomSummaryUpdater @Inject constructor(
        @UserId private val userId: String,
        private val roomDisplayNameResolver: SqlRoomDisplayNameResolver,
        private val roomAvatarResolver: SqlRoomAvatarResolver,
        private val roomAccountDataDataSource: RoomAccountDataDataSource,
        private val roomSummaryEventDecryptor: RoomSummaryEventDecryptor,
        private val roomSummaryEventsHelper: SqlRoomSummaryEventsHelper,
) {

    fun refreshLatestPreviewableEvent(stores: SessionStores, roomId: String, thorough: Boolean = false) {
        val entity = stores.roomSummary.get(roomId) ?: return
        val latestPreviewableEvent = roomSummaryEventsHelper.getLatestPreviewableEvent(stores, roomId, thorough)
        // Only advance when we actually found a previewable message — don't wipe a known-good last message
        // and its date just because the current chunk's newest events are non-previewable. Advancing the
        // activity time here is what lets opening a room correct a stale/missing room-list preview + date.
        if (latestPreviewableEvent != null) {
            entity.latestPreviewableEvent = latestPreviewableEvent
            latestPreviewableEvent.root?.originServerTs?.let { entity.lastActivityTime = it }
        }
        stores.roomSummary.upsert(entity)
    }

    fun refreshDisplay(stores: SessionStores, roomId: String) {
        val entity = stores.roomSummary.get(roomId) ?: return
        entity.setDisplayName(roomDisplayNameResolver.resolve(stores, roomId))
        entity.avatarUrl = roomAvatarResolver.resolve(stores, roomId)
        stores.roomSummary.upsert(entity)
    }

    fun updateSendingInformation(stores: SessionStores, roomId: String) {
        val entity = stores.roomSummary.get(roomId) ?: RoomSummaryEntity(roomId = roomId)
        entity.hasFailedSending = hasFailedSending(stores, roomId)
        entity.latestPreviewableEvent = roomSummaryEventsHelper.getLatestPreviewableEvent(stores, roomId)
        stores.roomSummary.upsert(entity)
    }

    fun update(
            stores: SessionStores,
            roomId: String,
            membership: Membership? = null,
            roomSummary: RoomSyncSummary? = null,
            unreadNotifications: RoomSyncUnreadNotifications? = null,
            unreadThreadNotifications: Map<String, RoomSyncUnreadThreadNotifications>? = null,
            updateMembers: Boolean = false,
            inviterId: String? = null,
            aggregator: SyncResponsePostTreatmentAggregator? = null,
            removedFromRoom: Boolean? = null,
    ) {
        val entity = stores.roomSummary.get(roomId) ?: RoomSummaryEntity(roomId = roomId)
        if (roomSummary != null) {
            if (roomSummary.heroes.isNotEmpty()) {
                entity.heroes = ArrayList<String>().apply { addAll(roomSummary.heroes) }
            }
            roomSummary.invitedMembersCount?.let { entity.invitedMembersCount = it }
            roomSummary.joinedMembersCount?.let { entity.joinedMembersCount = it }
        }
        // Sync v2 restates the counts on every room update, but sliding sync only sends them when they
        // change, so an absent block means "unchanged" rather than zero.
        if (unreadNotifications != null) {
            entity.highlightCount = unreadNotifications.highlightCount ?: 0
            entity.notificationCount = unreadNotifications.notificationCount ?: 0
        }
        entity.threadHighlightCount = unreadThreadNotifications?.count { (it.value.highlightCount ?: 0) > 0 } ?: 0
        entity.threadNotificationCount = unreadThreadNotifications?.count { (it.value.notificationCount ?: 0) > 0 } ?: 0
        if (membership != null) entity.membership = membership
        if (membership == Membership.JOIN) entity.isRemovedFromRoom = false
        if (removedFromRoom != null) entity.isRemovedFromRoom = removedFromRoom

        // Only virtual (VoIP-backing) rooms are hidden; an upgraded room stays listed, since hiding
        // it would strand the history that never moved to the successor.
        entity.isHiddenFromUser =
                roomAccountDataDataSource.getAccountDataEvent(roomId, RoomAccountDataTypes.EVENT_TYPE_VIRTUAL_ROOM) != null

        val lastNameEvent = stores.currentStateEvent.getOne(roomId, EventType.STATE_ROOM_NAME, "")?.root
        val lastTopicEvent = stores.currentStateEvent.getOne(roomId, EventType.STATE_ROOM_TOPIC, "")?.root
        val lastCanonicalAliasEvent = stores.currentStateEvent.getOne(roomId, EventType.STATE_ROOM_CANONICAL_ALIAS, "")?.root
        val roomCreateEvent = stores.currentStateEvent.getOne(roomId, EventType.STATE_ROOM_CREATE, "")?.root
        val joinRulesEvent = stores.currentStateEvent.getOne(roomId, EventType.STATE_ROOM_JOIN_RULES, "")?.root

        entity.roomType = ContentMapper.map(roomCreateEvent?.content).toModel<RoomCreateContent>()?.type

        val encryptionEvent = stores.currentStateEvent.getOne(roomId, EventType.STATE_ROOM_ENCRYPTION, "")?.root

        val latestPreviewableEvent = roomSummaryEventsHelper.getLatestPreviewableEvent(stores, roomId)
        // Only advance the activity time for an actual previewable message. When the recent activity is all
        // non-previewable (e.g. a big redaction batch), keep the last known message + time instead of
        // forgetting them — the room stays sorted by its real last message rather than dropping off the list.
        if (latestPreviewableEvent != null) {
            latestPreviewableEvent.root?.originServerTs?.let {
                entity.lastActivityTime = it
                latestPreviewableEvent.attemptToDecrypt()
            }
        }

        entity.hasUnreadMessages = entity.notificationCount > 0 ||
                latestPreviewableEvent?.let { !stores.isEventRead(userId, roomId, it.eventId) }.orFalse()

        if (entity.isRemovedFromRoom) {
            // A kicked/banned room is frozen and the server refuses our read receipts for it, so there is
            // nothing left to read and no way to record having read it.
            entity.highlightCount = 0
            entity.notificationCount = 0
            entity.threadHighlightCount = 0
            entity.threadNotificationCount = 0
            entity.hasUnreadMessages = false
            entity.markedUnread = false
        }

        entity.setDisplayName(roomDisplayNameResolver.resolve(stores, roomId))
        entity.avatarUrl = roomAvatarResolver.resolve(stores, roomId)
        entity.name = ContentMapper.map(lastNameEvent?.content).toModel<RoomNameContent>()?.name
        val topicContent = ContentMapper.map(lastTopicEvent?.content).toModel<RoomTopicContent>()
        entity.topic = topicContent?.getBestTopic()
        entity.topicFormatted = topicContent?.getBestFormattedTopic()
        entity.joinRules = ContentMapper.map(joinRulesEvent?.content).toModel<RoomJoinRulesContent>()?.joinRules
        // Only replace the preview when we actually found one — don't wipe a known-good last message just
        // because the current chunk's newest events are non-previewable.
        if (latestPreviewableEvent != null) {
            entity.latestPreviewableEvent = latestPreviewableEvent
        }
        entity.canonicalAlias = ContentMapper.map(lastCanonicalAliasEvent?.content).toModel<RoomCanonicalAliasContent>()?.canonicalAlias

        val wasEncrypted = entity.isEncrypted
        entity.isEncrypted = encryptionEvent != null
        if (encryptionEvent == null && entity.roomEncryptionTrustLevel != null) {
            entity.roomEncryptionTrustLevel = null
        }
        entity.e2eAlgorithm = ContentMapper.map(encryptionEvent?.content)?.toModel<EncryptionEventContent>()?.algorithm
        entity.encryptionEventTs = encryptionEvent?.originServerTs

        if (entity.membership == Membership.INVITE && inviterId != null) {
            entity.inviterId = inviterId
        } else if (entity.membership != Membership.INVITE) {
            entity.inviterId = null
        }
        entity.hasFailedSending = hasFailedSending(stores, roomId)

        if (updateMembers) {
            val otherRoomMembers = SqlRoomMemberHelper(stores, roomId)
                    .queryActiveRoomMembersEvent()
                    .filter { it.userId != userId }
                    .map { it.userId }
            entity.otherMemberIds = ArrayList<String>().apply { addAll(otherRoomMembers) }
            if (roomSummary?.joinedMembersCount == null) {
                entity.joinedMembersCount = otherRoomMembers.size + 1
            }
        }

        if (entity.isEncrypted && (!wasEncrypted || updateMembers || entity.roomEncryptionTrustLevel == null)) {
            aggregator?.roomsWithMembershipChangesForShieldUpdate?.add(roomId)
        }

        stores.roomSummary.upsert(entity)
    }

    private fun TimelineEventEntity.attemptToDecrypt() {
        root?.let { roomSummaryEventDecryptor.requestDecryption(it.asDomain()) }
    }

    private fun hasFailedSending(stores: SessionStores, roomId: String): Boolean =
            stores.timelineEvent.getSendingByRoom(roomId).any { it.root?.sendState in SendState.HAS_FAILED_STATES }

    fun validateSpaceRelationship(stores: SessionStores) {
        val active = Membership.activeMemberships()
        val summaries = stores.roomSummary.getAll()
                .filter { it.membership in active }
                .sortedBy { it.roomId }
        summaries.forEach {
            it.flattenParentIds = null
            it.directParentNames = mutableListOf()
        }
        val byId = summaries.associateBy { it.roomId }
        val relationChildren = summaries.associate { it.roomId to mutableSetOf<String>() }

        // Child relations
        summaries.filter { it.roomType == RoomType.SPACE }.forEach { lookedUp ->
            val children = ArrayList<SpaceChildSummaryEntity>()
            SqlRoomChildRelationInfo(stores, lookedUp.roomId).getDirectChildrenDescriptions().forEach { child ->
                children.add(SpaceChildSummaryEntity(
                        order = child.order,
                        childRoomId = child.roomId,
                        childSummaryEntity = byId[child.roomId]?.let { RoomSummaryEntity(roomId = it.roomId) },
                        viaServers = ArrayList<String>().apply { addAll(child.viaServers) },
                ))
                byId[child.roomId]?.takeIf { it.membership in active }?.let { childSum ->
                    relationChildren[lookedUp.roomId]?.add(childSum.roomId)
                }
            }
            lookedUp.children = children
        }

        // Parent relations
        summaries.forEach { lookedUp ->
            val parents = ArrayList<SpaceParentSummaryEntity>()
            SqlRoomChildRelationInfo(stores, lookedUp.roomId).getParentDescriptions().forEach { parentInfo ->
                val isValidRelation = if (relationChildren[parentInfo.roomId]?.contains(lookedUp.roomId) == true) {
                    true
                } else {
                    val powerLevelsContent = stores.currentStateEvent.getOne(parentInfo.roomId, EventType.STATE_ROOM_POWER_LEVELS, "")
                            ?.root?.let { ContentMapper.map(it.content).toModel<PowerLevelsContent>() }
                    val roomCreateContent = stores.currentStateEvent.getOne(parentInfo.roomId, EventType.STATE_ROOM_CREATE, "")
                            ?.root?.let {
                                val content = ContentMapper.map(it.content).toModel<RoomCreateContent>()
                                val sender = it.sender
                                if (content != null && sender != null) RoomCreateContentWithSender(sender, content) else null
                            }
                    RoomPowerLevels(powerLevelsContent, roomCreateContent).isUserAllowedToSend(parentInfo.stateEventSender, true, EventType.STATE_SPACE_CHILD)
                }
                if (isValidRelation) {
                    parents.add(SpaceParentSummaryEntity(
                            parentRoomId = parentInfo.roomId,
                            parentSummaryEntity = byId[parentInfo.roomId]?.let { RoomSummaryEntity(roomId = it.roomId) },
                            canonical = parentInfo.canonical,
                            viaServers = ArrayList<String>().apply { addAll(parentInfo.viaServers) },
                    ))
                    byId[parentInfo.roomId]?.takeIf { it.membership in active }?.let { parentSum ->
                        relationChildren[parentSum.roomId]?.add(lookedUp.roomId)
                    }
                }
            }
            lookedUp.parents = parents
        }

        // Break cycles + flatten
        val graph = Graph()
        summaries.filter { it.roomType == RoomType.SPACE && it.membership == Membership.JOIN }.forEach { sum ->
            graph.getOrCreateNode(sum.roomId)
            relationChildren[sum.roomId]?.forEach { childId -> graph.addEdge(childId, sum.roomId) }
        }
        val backEdges = graph.findBackwardEdges()
        backEdges.forEach { edge -> relationChildren[edge.source.name]?.removeAll { it == edge.destination.name } }
        val acyclicGraph = graph.withoutEdges(backEdges)
        val flattenSpaceParents = acyclicGraph.flattenDestination().map { it.key.name to it.value.map { node -> node.name } }.toMap()

        summaries.filter { it.roomType == RoomType.SPACE && it.membership == Membership.JOIN }.forEach { parent ->
            val flattenParentsIds = (flattenSpaceParents[parent.roomId] ?: emptyList()) + listOf(parent.roomId)
            relationChildren[parent.roomId]?.forEach { childId ->
                byId[childId]?.let { childSum ->
                    parent.displayName()?.let { childSum.directParentNames.add(it) }
                    if (childSum.flattenParentIds == null) childSum.flattenParentIds = ""
                    flattenParentsIds.forEach { if (childSum.flattenParentIds?.contains(it) != true) childSum.flattenParentIds += "|$it" }
                }
            }
        }

        // Space notification counts
        summaries.filter { it.roomType == RoomType.SPACE && it.membership in active }.forEach { space ->
            var highlightCount = 0
            var notificationCount = 0
            summaries.filter { it.membership == Membership.JOIN && it.roomType != RoomType.SPACE && it.flattenParentIds?.contains(space.roomId) == true }
                    .forEach { highlightCount += it.highlightCount; notificationCount += it.notificationCount }
            space.highlightCount = highlightCount
            space.notificationCount = notificationCount
        }

        summaries.forEach { stores.roomSummary.upsert(it) }
        Timber.v("## SPACES: Finished SQL room hierarchy validation")
    }
}
