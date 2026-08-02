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
import javax.inject.Inject

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
