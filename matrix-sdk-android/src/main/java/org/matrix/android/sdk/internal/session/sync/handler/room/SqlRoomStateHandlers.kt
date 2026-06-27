/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync.handler.room

import org.matrix.android.sdk.api.session.room.model.tag.RoomTagContent
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.session.room.read.FullyReadContent
import org.matrix.android.sdk.internal.session.room.read.MarkedUnreadContent
import timber.log.Timber
import javax.inject.Inject

/** SQLDelight write-path counterpart of the former Realm RoomTagHandler. */
internal class SqlRoomTagHandler @Inject constructor() {
    fun handle(stores: SessionStores, roomId: String, content: RoomTagContent?) {
        content ?: return
        val tags = content.tags.entries.map { (tagName, params) -> tagName to (params["order"] as? Double) }
        stores.roomSummary.updateTags(roomId, tags)
    }
}

/** SQLDelight write-path counterpart of the former Realm RoomFullyReadHandler. */
internal class SqlRoomFullyReadHandler @Inject constructor() {
    fun handle(stores: SessionStores, roomId: String, content: FullyReadContent?) {
        content ?: return
        Timber.v("Handle for roomId: $roomId eventId: ${content.eventId}")
        stores.roomSummary.updateReadMarkerId(roomId, content.eventId)
        stores.readMarker.upsert(roomId, content.eventId)
    }
}

/** SQLDelight write-path counterpart of the former Realm RoomMarkedUnreadHandler. */
internal class SqlRoomMarkedUnreadHandler @Inject constructor() {
    fun handle(stores: SessionStores, roomId: String, content: MarkedUnreadContent?) {
        content ?: return
        Timber.v("Handle for roomId: $roomId markedUnread: ${content.markedUnread}")
        stores.roomSummary.updateMarkedUnread(roomId, content.markedUnread)
    }
}
