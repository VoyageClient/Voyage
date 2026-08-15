/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.accountdata

import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.room.accountdata.RoomAccountDataTypes
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.sql.store.SessionStores

/** MSC3015: personal room name/avatar stored in room account data. */
internal object RoomStateOverrides {

    private val NAME_TYPES = listOf(
            RoomAccountDataTypes.EVENT_TYPE_ROOM_NAME_OVERRIDE,
            RoomAccountDataTypes.EVENT_TYPE_ROOM_NAME_OVERRIDE_UNSTABLE,
    )

    private val AVATAR_TYPES = listOf(
            RoomAccountDataTypes.EVENT_TYPE_ROOM_AVATAR_OVERRIDE,
            RoomAccountDataTypes.EVENT_TYPE_ROOM_AVATAR_OVERRIDE_UNSTABLE,
    )

    val ALL_TYPES = NAME_TYPES + AVATAR_TYPES

    fun roomName(stores: SessionStores, roomId: String): String? =
            (content(stores, roomId, NAME_TYPES)?.get("name") as? String)?.takeIf { it.isNotBlank() }

    fun roomAvatar(stores: SessionStores, roomId: String): String? =
            (content(stores, roomId, AVATAR_TYPES)?.get("url") as? String)?.takeIf { it.isNotBlank() }

    private fun content(stores: SessionStores, roomId: String, types: List<String>): Content? {
        val rows = stores.accountData.getRoomAccountData(roomId)
        for (type in types) {
            val content = rows.firstOrNull { it.type == type }?.contentStr?.let(ContentMapper::map)
            if (!content.isNullOrEmpty()) return content
        }
        return null
    }
}
