/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.user.accountdata

import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.session.sync.model.accountdata.DirectMessagesContent
import javax.inject.Inject

internal class DirectChatsHelper @Inject constructor(
        private val stores: SessionStores,
        private val directRoomsCache: org.matrix.android.sdk.internal.session.room.summary.DirectRoomsCache,
) {

    /**
     * Merge stored m.direct with local summaries: PUT replaces the whole map,
     * so rebuilding from a partially synced room list would erase unseen DMs.
     */
    fun getDirectMessagesToPut(filterRoomId: String? = null): DirectMessagesContent {
        val stored = stores.accountData.getUserAccountData(UserAccountDataTypes.TYPE_DIRECT_MESSAGES)
                ?.let { ContentMapper.map(it.contentStr) }
                ?.toModel<DirectMessagesContent>()
                .orEmpty()
        val merged: MutableMap<String, MutableList<String>> = stored
                .mapValuesTo(mutableMapOf()) { (_, roomIds) -> roomIds.toMutableList() }
        stores.roomSummary.directRooms()
                .filter { (roomId, directUserId, membershipStr) ->
                    roomId != filterRoomId && directUserId != null && Membership.valueOf(membershipStr).isActive()
                }
                .forEach { (roomId, directUserId, _) ->
                    merged.getOrPut(directUserId!!) { mutableListOf() }.let { if (roomId !in it) it.add(roomId) }
                }
        if (filterRoomId != null) {
            merged.values.forEach { it.remove(filterRoomId) }
            merged.entries.removeAll { it.value.isEmpty() }
        }
        return merged
    }

    /**
     * Write-through for an m.direct PUT. refreshDirectChatRooms() re-applies the *stored* copy after
     * sliding-sync batches, so leaving it stale until the server echoes the PUT back would let that
     * pass wipe the direct flags just set locally (e.g. on a freshly created DM).
     */
    fun storeLocally(directMessages: DirectMessagesContent) {
        stores.accountData.upsertUserAccountData(UserAccountDataTypes.TYPE_DIRECT_MESSAGES, ContentMapper.map(directMessages))
        directRoomsCache.invalidate()
    }
}
