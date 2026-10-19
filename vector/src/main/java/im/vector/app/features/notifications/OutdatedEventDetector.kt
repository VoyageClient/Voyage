/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.notifications

import im.vector.app.ActiveSessionDataSource
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.Room
import javax.inject.Inject

class OutdatedEventDetector @Inject constructor(
        private val activeSessionDataSource: ActiveSessionDataSource
) {

    /**
     * Returns true if the given event is outdated.
     * Used to clean up notifications if a displayed message has been read on an
     * other device.
     */
    fun isMessageOutdated(notifiableEvent: NotifiableEvent, roomCache: MutableMap<String, Room?> = mutableMapOf()): Boolean {
        val session = activeSessionDataSource.currentValue?.orNull() ?: return false

        if (notifiableEvent is NotifiableMessageEvent) {
            val eventID = notifiableEvent.eventId
            val roomID = notifiableEvent.roomId
            // Building a Room wires up every room service; a backlog of hundreds of notifications would build one per event.
            val room = roomCache.getOrPut(roomID) { session.getRoom(roomID) } ?: return false
            return room.readService().isEventRead(eventID, notifiableEvent.threadId)
        }
        return false
    }
}
