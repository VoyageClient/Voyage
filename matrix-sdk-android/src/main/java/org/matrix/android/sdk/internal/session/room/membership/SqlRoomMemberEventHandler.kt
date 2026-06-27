/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.membership

import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.events.getFixedRoomMemberContent
import org.matrix.android.sdk.internal.session.sync.SyncResponsePostTreatmentAggregator
import org.matrix.android.sdk.internal.session.user.UserEntityFactory
import javax.inject.Inject

/** SQLDelight write-path counterpart of [RoomMemberEventHandler]. Runs inside the session DB transaction. */
internal class SqlRoomMemberEventHandler @Inject constructor(
        @UserId private val myUserId: String,
) {

    fun handle(
            stores: SessionStores,
            roomId: String,
            event: Event,
            isInitialSync: Boolean,
            aggregator: SyncResponsePostTreatmentAggregator? = null,
    ): Boolean {
        if (event.type != EventType.STATE_ROOM_MEMBER) return false
        val eventUserId = event.stateKey ?: return false
        val roomMember = event.getFixedRoomMemberContent() ?: return false

        if (isInitialSync) {
            if (myUserId != eventUserId && roomMember.membership.isActive()) {
                saveUserLocally(stores, eventUserId, roomMember)
            }
        } else {
            checkProfileChange(aggregator, eventUserId, roomMember, event.resolvedPrevContent())
        }
        saveRoomMemberEntityLocally(stores, roomId, eventUserId, roomMember)
        updateDirectChatsIfNecessary(roomId, roomMember, aggregator)
        return true
    }

    private fun saveRoomMemberEntityLocally(stores: SessionStores, roomId: String, userId: String, roomMember: RoomMemberContent) {
        val existing = stores.roomMember.getByRoomAndUser(roomId, userId)
        if (existing != null) {
            existing.displayName = roomMember.displayName
            existing.avatarUrl = roomMember.avatarUrl
            existing.membership = roomMember.membership
            stores.roomMember.upsert(existing)
        } else {
            val presence = stores.user.getPresence(userId)
            stores.roomMember.upsert(RoomMemberEntityFactory.create(roomId, userId, roomMember, presence))
        }
    }

    private fun saveUserLocally(stores: SessionStores, userId: String, roomMember: RoomMemberContent) {
        stores.user.upsertUser(UserEntityFactory.create(userId, roomMember))
    }

    private fun checkProfileChange(
            aggregator: SyncResponsePostTreatmentAggregator?,
            eventUserId: String,
            roomMember: RoomMemberContent,
            prevContent: Content?,
    ) {
        aggregator ?: return
        val previousDisplayName = prevContent?.get("displayname") as? String
        val previousAvatar = prevContent?.get("avatar_url") as? String
        if ((previousDisplayName != null && previousDisplayName != roomMember.displayName) ||
                (previousAvatar != null && previousAvatar != roomMember.avatarUrl)) {
            aggregator.userIdsToFetch.add(eventUserId)
        }
    }

    private fun updateDirectChatsIfNecessary(roomId: String, roomMember: RoomMemberContent, aggregator: SyncResponsePostTreatmentAggregator?) {
        val mxId = roomMember.thirdPartyInvite?.signed?.mxid
        if (mxId != null && mxId != myUserId) {
            aggregator?.directChatsToCheck?.put(roomId, mxId)
        }
    }
}
