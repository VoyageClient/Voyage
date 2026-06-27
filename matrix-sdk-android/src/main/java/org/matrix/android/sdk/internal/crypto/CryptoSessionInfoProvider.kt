/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.crypto

import org.matrix.android.sdk.api.session.crypto.model.RoomEncryptionTrustLevel
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sql.store.splitToList
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.room.membership.SqlRoomMemberHelper
import javax.inject.Inject

/**
 * The crypto module needs some information regarding rooms that are stored
 * in the session DB, this class encapsulate this functionality.
 */
internal class CryptoSessionInfoProvider @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        private val stores: SessionStores,
        @UserId private val myUserId: String,
) {

    fun isRoomEncrypted(roomId: String): Boolean {
        // presence of any m.room.encryption state event (state_key empty)
        return stores.currentStateEvent.getOne(roomId, EventType.STATE_ROOM_ENCRYPTION, "") != null
    }

    /**
     * @param roomId the room Id
     * @param allActive if true return joined as well as invited, if false, only joined
     */
    fun getRoomUserIds(roomId: String, allActive: Boolean): List<String> {
        val helper = SqlRoomMemberHelper(stores, roomId)
        return if (allActive) helper.getActiveRoomMemberIds() else helper.getJoinedRoomMemberIds()
    }

    fun getUserListForShieldComputation(roomId: String): List<String> {
        val userIds = SqlRoomMemberHelper(stores, roomId).getActiveRoomMemberIds()
        val isDirect = stores.roomSummary.get(roomId)?.isDirect == true
        return if (isDirect || userIds.size <= 2) {
            userIds.filter { it != myUserId }
        } else {
            userIds
        }
    }

    fun getRoomsWhereUsersAreParticipating(userList: List<String>): List<String> {
        val active = Membership.activeMemberships().map { it.name }
        val rows = database.roomSummaryQueries.selectRoomMembershipInfo().executeAsList()
                .filter { it.membership_str in active }
        return if (userList.contains(myUserId)) {
            rows.map { it.room_id }
        } else {
            rows.filter { row -> row.other_member_ids.splitToList().any { it in userList } }
                    .map { it.room_id }
        }
    }

    fun markMessageVerificationStateAsDirty(userList: List<String>) {
        if (userList.isEmpty()) return
        database.eventQueries.markVerificationDirtyForSenders(userList, EventType.ENCRYPTED)
    }

    fun updateShieldForRoom(roomId: String, shield: RoomEncryptionTrustLevel?) {
        stores.roomSummary.updateEncryptionTrustLevel(roomId, shield?.name)
    }
}
