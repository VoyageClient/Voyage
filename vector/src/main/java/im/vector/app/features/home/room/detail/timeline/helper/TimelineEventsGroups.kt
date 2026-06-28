/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.getRelationContent
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent

class TimelineEventsGroup(val groupId: String) {

    val events: Set<TimelineEvent>
        get() = _events

    private val _events = HashSet<TimelineEvent>()

    fun add(timelineEvent: TimelineEvent) {
        _events.add(timelineEvent)
    }
}

class TimelineEventsGroups {

    private val groups = HashMap<String, TimelineEventsGroup>()

    fun addOrIgnore(event: TimelineEvent) {
        val groupId = event.getGroupIdOrNull() ?: return
        groups.getOrPut(groupId) { TimelineEventsGroup(groupId) }.add(event)
    }

    fun getOrNull(event: TimelineEvent): TimelineEventsGroup? {
        val groupId = event.getGroupIdOrNull() ?: return null
        return groups[groupId]
    }

    private fun TimelineEvent.getGroupIdOrNull(): String? {
        val type = root.getClearType()
        val relationContent = root.getRelationContent()
        return when {
            type == EventType.STATE_ROOM_WIDGET || type == EventType.STATE_ROOM_WIDGET_LEGACY -> root.stateKey
            type == EventType.ENCRYPTED && relationContent?.type == RelationType.REFERENCE -> {
                relationContent.eventId
            }
            else -> {
                null
            }
        }
    }

    fun clear() {
        groups.clear()
    }
}
