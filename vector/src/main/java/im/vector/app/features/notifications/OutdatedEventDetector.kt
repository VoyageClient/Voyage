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

    fun readEventIds(events: List<NotifiableMessageEvent>): Set<String> {
        val session = activeSessionDataSource.currentValue?.orNull() ?: return emptySet()
        val rooms = mutableMapOf<String, Room?>()
        return events.groupBy { it.roomId to it.threadId }.flatMap { (key, messages) ->
            rooms.getOrPut(key.first) { session.getRoom(key.first) }?.readService()
                    ?.getReadEventIds(messages.map { it.eventId }, key.second).orEmpty()
        }.toSet()
    }
}
