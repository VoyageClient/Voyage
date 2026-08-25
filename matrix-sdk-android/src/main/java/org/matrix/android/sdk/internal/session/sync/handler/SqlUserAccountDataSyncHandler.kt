/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync.handler

import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataEvent
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.pushrules.RuleScope
import org.matrix.android.sdk.api.session.pushrules.RuleSetKey
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.sync.model.InvitedRoomSync
import org.matrix.android.sdk.api.session.sync.model.UserAccountDataSync
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.mapper.PushRulesMapper
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.model.PushRulesEntity
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.profile.ProfileOverridesUpdater
import org.matrix.android.sdk.internal.session.pushers.GetPushRulesResponse
import org.matrix.android.sdk.internal.session.room.SqlRoomAvatarResolver
import org.matrix.android.sdk.internal.session.room.membership.SqlRoomDisplayNameResolver
import org.matrix.android.sdk.internal.session.room.membership.SqlRoomMemberHelper
import org.matrix.android.sdk.internal.session.sync.SyncResponsePostTreatmentAggregator
import org.matrix.android.sdk.internal.session.sync.model.accountdata.BreadcrumbsContent
import org.matrix.android.sdk.internal.session.sync.model.accountdata.DirectMessagesContent
import org.matrix.android.sdk.internal.session.sync.model.accountdata.IgnoredUsersContent
import org.matrix.android.sdk.internal.session.sync.model.accountdata.toMutable
import org.matrix.android.sdk.internal.session.user.accountdata.DirectChatsHelper
import org.matrix.android.sdk.internal.session.user.accountdata.IgnoredUsersApplier
import org.matrix.android.sdk.internal.session.user.accountdata.IgnoredUsersUpdater
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
        private val ignoredUsersUpdater: IgnoredUsersUpdater,
        private val ignoredUsersApplier: IgnoredUsersApplier,
        private val profileOverridesUpdater: ProfileOverridesUpdater,
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
                directChatsHelper.storeLocally(directChats)
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
                UserAccountDataTypes.TYPE_PROFILE_OVERRIDES,
                UserAccountDataTypes.TYPE_PROFILE_OVERRIDES_UNSTABLE -> profileOverridesUpdater.apply()
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
            rules?.filterNot { it.ruleId in org.matrix.android.sdk.api.session.pushrules.RuleIds.LEGACY_MENTION_RULE_IDS }
                    ?.forEach { entity.pushRules.add(PushRulesMapper.map(it)) }
            stores.pushRules.upsert(entity)
        }
        save(RuleSetKey.CONTENT, global.content)
        save(RuleSetKey.OVERRIDE, global.override)
        save(RuleSetKey.ROOM, global.room)
        save(RuleSetKey.SENDER, global.sender)
        save(RuleSetKey.UNDERRIDE, global.underride)
    }

    /**
     * Re-applies the stored `m.direct` against whatever rooms exist now. Sliding sync hands account data
     * over in the first response and rooms over gradually after it, so a DM that arrived in a later batch
     * was not there to be marked when `m.direct` was processed.
     */
    fun refreshDirectChatRooms() {
        val stored = stores.accountData.getUserAccountData(UserAccountDataTypes.TYPE_DIRECT_MESSAGES) ?: return
        val content = ContentMapper.map(stored.contentStr) ?: return
        handleDirectChatRooms(UserAccountDataEvent(type = UserAccountDataTypes.TYPE_DIRECT_MESSAGES, content = content))
    }

    private fun handleDirectChatRooms(event: UserAccountDataEvent) {
        val content = event.content.toModel<DirectMessagesContent>() ?: return
        val directRoomIds = content.values.flatten().toSet()
        content.forEach { (directUserId, roomIds) ->
            roomIds.forEach { roomId ->
                stores.roomSummary.get(roomId)?.let { entity ->
                    entity.isDirect = true
                    entity.directUserId = directUserId
                    // Persist the direct flags before resolving: the resolvers re-read room_summary from
                    // the DB, so they only pick the DM peer's avatar/name once is_direct is actually stored.
                    stores.roomSummary.upsert(entity)
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
            stores.roomSummary.upsert(entity)
            entity.avatarUrl = roomAvatarResolver.resolve(stores, entity.roomId)
            entity.setDisplayName(roomDisplayNameResolver.resolve(stores, entity.roomId))
            stores.roomSummary.upsert(entity)
        }
    }

    private fun handleIgnoredUsers(event: UserAccountDataEvent, aggregator: SyncResponsePostTreatmentAggregator) {
        // Drop blank ids: a malformed "" key in the account-data map would otherwise be stored and then
        // crash / blank the ignored-users screen (User("") fails MatrixItem's @-prefix check).
        val newIgnoredUserIds = event.content.toModel<IgnoredUsersContent>()?.ignoredUsers?.keys
                ?.filter { it.isNotBlank() } ?: return
        // The list just changed server-side; drop the cached base so the next local update re-reads it.
        ignoredUsersUpdater.lastKnownIds = null
        val newlyUnIgnored = ignoredUsersApplier.apply(stores, newIgnoredUserIds)
        // Nothing stored is deleted: the timeline filters ignored senders at display time, so their
        // already-synced messages come back the moment they are un-ignored. What the server withheld
        // while they were ignored is never re-sent, so flag them for the post-transaction catch-up.
        aggregator.unIgnoredUserIds.addAll(newlyUnIgnored)
    }

    private fun handleBreadcrumbs(event: UserAccountDataEvent) {
        val recentRoomIds = event.content.toModel<BreadcrumbsContent>()?.recentRoomIds ?: return
        stores.breadcrumbs.set(recentRoomIds)
        stores.roomSummary.resetBreadcrumbsIndex()
        recentRoomIds.forEachIndexed { index, roomId -> stores.roomSummary.updateBreadcrumbsIndex(roomId, index) }
    }
}
