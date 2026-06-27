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

package org.matrix.android.sdk.internal.session.room

import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.localecho.RoomLocalEcho
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sql.store.splitToList
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.SessionScope
import javax.inject.Inject

internal interface RoomGetter {
    fun getRoom(roomId: String): Room?

    fun getDirectRoomWith(otherUserId: String): String?
}

@SessionScope
internal class DefaultRoomGetter @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        private val stores: SessionStores,
        private val roomFactory: RoomFactory,
) : RoomGetter {

    override fun getRoom(roomId: String): Room? =
            stores.room.get(roomId)?.let { roomFactory.create(roomId) }

    override fun getDirectRoomWith(otherUserId: String): String? {
        return database.roomSummaryQueries.selectAll().executeAsList()
                .firstOrNull { dm ->
                    dm.is_direct == 1L &&
                            dm.membership_str == Membership.JOIN.name &&
                            !RoomLocalEcho.isLocalEchoId(dm.room_id) &&
                            dm.other_member_ids.splitToList().let { it.size == 1 && it.first() == otherUserId }
                }
                ?.room_id
    }
}
