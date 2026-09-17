/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.summary

import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.sync.model.accountdata.DirectMessagesContent
import javax.inject.Inject

/** Cache m.direct by room to avoid parsing the full account map for every summary. */
@SessionScope
internal class DirectRoomsCache @Inject constructor() {

    @Volatile
    private var byRoomId: Map<String, String>? = null

    fun directUserId(stores: SessionStores, roomId: String): String? = map(stores)[roomId]

    /** Call whenever `m.direct` is written; the next read rebuilds. */
    fun invalidate() {
        byRoomId = null
    }

    private fun map(stores: SessionStores): Map<String, String> {
        byRoomId?.let { return it }
        val content = stores.accountData.getUserAccountData(UserAccountDataTypes.TYPE_DIRECT_MESSAGES)
                ?.let { ContentMapper.map(it.contentStr) }
                ?.toModel<DirectMessagesContent>()
        val built = buildMap {
            content?.forEach { (userId, roomIds) ->
                roomIds.forEach { roomId -> putIfAbsent(roomId, userId) }
            }
        }
        byRoomId = built
        return built
    }
}
