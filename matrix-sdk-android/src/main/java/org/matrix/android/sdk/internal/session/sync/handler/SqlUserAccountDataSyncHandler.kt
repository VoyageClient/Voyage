/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync.handler

import org.matrix.android.sdk.api.session.accountdata.UserAccountDataEvent
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.pushrules.RuleScope
import org.matrix.android.sdk.api.session.pushrules.RuleSetKey
import org.matrix.android.sdk.api.session.sync.model.UserAccountDataSync
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.mapper.PushRulesMapper
import org.matrix.android.sdk.internal.database.model.PushRulesEntity
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.session.pushers.GetPushRulesResponse
import org.matrix.android.sdk.internal.session.room.SqlRoomAvatarResolver
import org.matrix.android.sdk.internal.session.room.membership.SqlRoomDisplayNameResolver
import org.matrix.android.sdk.internal.session.room.summary.SqlRoomSummaryUpdater
import org.matrix.android.sdk.internal.session.sync.SyncResponsePostTreatmentAggregator
import org.matrix.android.sdk.internal.session.sync.model.accountdata.BreadcrumbsContent
import org.matrix.android.sdk.internal.session.sync.model.accountdata.DirectMessagesContent
import org.matrix.android.sdk.internal.session.sync.model.accountdata.IgnoredUsersContent
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.sync.model.InvitedRoomSync
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.room.membership.SqlRoomMemberHelper
import org.matrix.android.sdk.internal.session.sync.model.accountdata.toMutable
import org.matrix.android.sdk.internal.session.user.accountdata.DirectChatsHelper
import org.matrix.android.sdk.internal.session.user.accountdata.UpdateUserAccountDataTask
import javax.inject.Inject

/** SQLDelight write-path counterpart of [UserAccountDataSyncHandler]. */
internal class SqlUserAccountDataSyncHandler @Inject constructor(
        private val stores: SessionStores,
        private val roomAvatarResolver: SqlRoomAvatarResolver,
        private val roomDisplayNameResolver: SqlRoomDisplayNameResolver,
        @UserId private val userId: String,
        private val directChatsHelper: DirectChatsHelper,
        private val updateUserAccountDataTask: UpdateUserAccountDataTask,
        private val roomSummaryUpdater: SqlRoomSummaryUpdater,
) {

    // If we get some direct chat invites, synchronize the user account data including those.
    suspend fun synchronizeWithServerIfNeeded(invites: Map<String, InvitedRoomSync>) {
        if (invites.isEmpty()) return
        val directChats = directChatsHelper.getLocalDirectMessages().toMutable()
        var hasUpdate = false
        invites.forEach { (roomId, _) ->
            val myUserStateEvent = SqlRoomMemberHelper(stores, roomId).getLastStateEvent(userId)
            val inviterId = myUserStateEvent?.sender
            val myUserRoomMember: RoomMemberContent? = myUserStateEvent?.let { it.asDomain().content?.toModel() }
            if (inviterId != null && inviterId != userId && myUserRoomMember?.isDirect == true) {
                directChats.getOrPut(inviterId) { arrayListOf() }.apply {
                    if (!contains(roomId)) {
                        add(roomId)
                        hasUpdate = true
                    }
                }
            }
        }
        if (hasUpdate) {
            tryOrNull("Unable to update user account data") {
                updateUserAccountDataTask.execute(UpdateUserAccountDataTask.DirectChatParams(directMessages = directChats))
            }
        }
    }

    fun handle(accountData: UserAccountDataSync?, aggregator: SyncResponsePostTreatmentAggregator) {
        accountData?.list?.forEach { event ->
            handleGenericAccountData(event.type, event.content)
            when (event.type) {
                UserAccountDataTypes.TYPE_DIRECT_MESSAGES -> handleDirectChatRooms(event)
                UserAccountDataTypes.TYPE_PUSH_RULES -> handlePushRules(event)
                UserAccountDataTypes.TYPE_IGNORED_USER_LIST -> handleIgnoredUsers(event, aggregator)
                UserAccountDataTypes.TYPE_BREADCRUMBS -> handleBreadcrumbs(event)
            }
        }
    }

    private fun handleGenericAccountData(type: String, content: Content?) {
        if (content.isNullOrEmpty()) {
            stores.accountData.deleteUserAccountData(type)
        } else {
            stores.accountData.upsertUserAccountData(type, ContentMapper.map(content))
        }
    }

    private fun handlePushRules(event: UserAccountDataEvent) {
        val pushRules = event.content.toModel<GetPushRulesResponse>() ?: return
        val global = pushRules.global
        fun save(kind: RuleSetKey, rules: List<org.matrix.android.sdk.api.session.pushrules.rest.PushRule>?) {
            val entity = PushRulesEntity(RuleScope.GLOBAL).apply { this.kind = kind }
            rules?.forEach { entity.pushRules.add(PushRulesMapper.map(it)) }
            stores.pushRules.upsert(entity)
        }
        save(RuleSetKey.CONTENT, global.content)
        save(RuleSetKey.OVERRIDE, global.override)
        save(RuleSetKey.ROOM, global.room)
        save(RuleSetKey.SENDER, global.sender)
        save(RuleSetKey.UNDERRIDE, global.underride)
    }

    private fun handleDirectChatRooms(event: UserAccountDataEvent) {
        val content = event.content.toModel<DirectMessagesContent>() ?: return
        val directRoomIds = content.values.flatten().toSet()
        content.forEach { (directUserId, roomIds) ->
            roomIds.forEach { roomId ->
                stores.roomSummary.get(roomId)?.let { entity ->
                    entity.isDirect = true
                    entity.directUserId = directUserId
                    entity.avatarUrl = roomAvatarResolver.resolve(stores, roomId)
                    entity.setDisplayName(roomDisplayNameResolver.resolve(stores, roomId))
                    stores.roomSummary.upsert(entity)
                }
            }
        }
        // Reset rooms that are no longer direct
        stores.roomSummary.getAll().filter { it.isDirect && it.roomId !in directRoomIds }.forEach { entity ->
            entity.isDirect = false
            entity.directUserId = null
            entity.avatarUrl = roomAvatarResolver.resolve(stores, entity.roomId)
            entity.setDisplayName(roomDisplayNameResolver.resolve(stores, entity.roomId))
            stores.roomSummary.upsert(entity)
        }
    }

    private fun handleIgnoredUsers(event: UserAccountDataEvent, aggregator: SyncResponsePostTreatmentAggregator) {
        val newIgnoredUserIds = event.content.toModel<IgnoredUsersContent>()?.ignoredUsers?.keys ?: return
        val currentIgnoredUserIds = stores.user.getIgnoredUserIds()
        val newlyUnIgnored = currentIgnoredUserIds.filter { it !in newIgnoredUserIds }
        val newlyIgnored = newIgnoredUserIds.filter { it !in currentIgnoredUserIds }
        currentIgnoredUserIds.forEach { stores.user.deleteIgnoredUser(it) }
        newIgnoredUserIds.forEach { stores.user.insertIgnoredUser(it) }
        // Re-evaluate the room-list preview and hide/show inbound invites for the changed users. Writing
        // room_summary here is what makes this work regardless of which client did the (un)ignore: the
        // room list observes room_summary, but NOT the ignored_user table. (The open timeline re-filters
        // itself separately via the ignored_user flow.)
        val changedUsers = newlyIgnored + newlyUnIgnored
        if (changedUsers.isNotEmpty()) {
            // Drop a newly-ignored author's message wherever it is the current preview (keyed on the stored
            // preview's author, so it doesn't depend on possibly-stale other_member_ids), plus re-evaluate
            // rooms a changed user belongs to (un-ignore may restore their message as the preview).
            val roomsToRefresh = LinkedHashSet<String>()
            roomsToRefresh += stores.roomSummary.roomIdsWithPreviewFromSenders(newlyIgnored)
            roomsToRefresh += stores.roomSummary.roomIdsWithActiveMembers(changedUsers)
            roomsToRefresh.forEach { roomSummaryUpdater.refreshLatestPreviewableEvent(stores, it) }
            // Inbound invites: hide those from a newly-ignored inviter, reveal them again on un-ignore.
            stores.roomSummary.inviteRoomIdsByInviters(newlyIgnored).forEach { stores.roomSummary.setHiddenFromUser(it, true) }
            stores.roomSummary.inviteRoomIdsByInviters(newlyUnIgnored).forEach { stores.roomSummary.setHiddenFromUser(it, false) }
        }
        // No event deletion + no forced initial sync (the old behavior): the timeline filters ignored
        // senders' messages at display time and observes this `ignored_user` table, so ignoring hides
        // their messages and UNIGNORING reveals already-cached ones instantly — without re-syncing.
        // Content the server suppressed while they were ignored (invites; messages interleaved in
        // already-synced ranges) is never re-sent by an incremental sync, so flag the un-ignored users
        // for a post-transaction targeted catch-up (FetchUnignoredContentTask) that recovers it.
        if (newlyUnIgnored.isNotEmpty()) {
            aggregator.unIgnoredUserIds.addAll(newlyUnIgnored)
        }
    }

    private fun handleBreadcrumbs(event: UserAccountDataEvent) {
        val recentRoomIds = event.content.toModel<BreadcrumbsContent>()?.recentRoomIds ?: return
        stores.breadcrumbs.set(recentRoomIds)
        stores.roomSummary.resetBreadcrumbsIndex()
        recentRoomIds.forEachIndexed { index, roomId -> stores.roomSummary.updateBreadcrumbsIndex(roomId, index) }
    }
}
