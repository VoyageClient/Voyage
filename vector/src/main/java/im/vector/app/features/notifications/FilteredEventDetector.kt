/*
 * Copyright 2023, 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.notifications

import im.vector.app.ActiveSessionDataSource
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.getTimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import javax.inject.Inject

class FilteredEventDetector @Inject constructor(
        private val activeSessionDataSource: ActiveSessionDataSource
) {

    /**
     * Returns true if the given event should be ignored.
     * Used to skip notifications if a non expected message is received.
     */
    fun shouldBeIgnored(notifiableEvent: NotifiableEvent): Boolean {
        val session = activeSessionDataSource.currentValue?.orNull() ?: return false

        if (notifiableEvent is NotifiableMessageEvent) {
            val room = session.getRoom(notifiableEvent.roomId) ?: return false
            val timelineEvent = room.getTimelineEvent(notifiableEvent.eventId) ?: return false
            return timelineEvent.shouldBeIgnored()
        }
        return false
    }

    /**
     * Whether the timeline event should be ignored.
     */
    private fun TimelineEvent.shouldBeIgnored(): Boolean {
        return false
    }
}
