/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
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
import org.matrix.android.sdk.internal.session.room.peeking.PeekedRoomManager
import javax.inject.Inject

@SessionScope
internal class DefaultRoomGetter @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        private val stores: SessionStores,
        private val roomFactory: RoomFactory,
        private val peekedRoomManager: PeekedRoomManager,
) : RoomGetter {

    override fun getRoom(roomId: String): Room? =
            stores.room.get(roomId)?.let { roomFactory.create(roomId) }
                    ?: peekedRoomManager.get(roomId)

    override fun getDirectRoomWith(otherUserId: String): String? {
        val joinedDirects = database.roomSummaryQueries.selectAll().executeAsList().filter {
            it.is_direct == 1L && it.membership_str == Membership.JOIN.name && !RoomLocalEcho.isLocalEchoId(it.room_id)
        }
        // What m.direct says this room is a DM with wins: such a room may well have picked up more members since.
        return (joinedDirects.firstOrNull { it.direct_user_id == otherUserId }
                ?: joinedDirects.firstOrNull { dm ->
                    dm.other_member_ids.splitToList().let { it.size == 1 && it.first() == otherUserId }
                })
                ?.room_id
    }
}
