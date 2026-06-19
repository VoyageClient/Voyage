/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import im.vector.app.features.voicebroadcast.VoiceBroadcastConstants
import im.vector.app.features.voicebroadcast.duration
import im.vector.app.features.voicebroadcast.getVoiceBroadcastEventId
import im.vector.app.features.voicebroadcast.isVoiceBroadcast
import im.vector.app.features.voicebroadcast.model.VoiceBroadcastState
import im.vector.app.features.voicebroadcast.model.asVoiceBroadcastEvent
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.getRelationContent
import org.matrix.android.sdk.api.session.room.model.message.asMessageAudioEvent
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
            type == VoiceBroadcastConstants.STATE_ROOM_VOICE_BROADCAST_INFO -> root.asVoiceBroadcastEvent()?.reference?.eventId
            type == EventType.STATE_ROOM_WIDGET || type == EventType.STATE_ROOM_WIDGET_LEGACY -> root.stateKey
            type == EventType.MESSAGE && root.asMessageAudioEvent().isVoiceBroadcast() -> {
                // Group voice messages with a reference to an eventId
                root.asMessageAudioEvent()?.getVoiceBroadcastEventId()
            }
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

class VoiceBroadcastEventsGroup(private val group: TimelineEventsGroup) {

    val voiceBroadcastId = group.groupId

    fun getLastDisplayableEvent(): TimelineEvent {
        return group.events.find { it.root.asVoiceBroadcastEvent()?.content?.voiceBroadcastState == VoiceBroadcastState.STOPPED }
                ?: group.events.filter { it.root.type == VoiceBroadcastConstants.STATE_ROOM_VOICE_BROADCAST_INFO }.maxBy { it.root.originServerTs ?: 0L }
    }

    fun getDuration(): Int {
        return group.events.mapNotNull { it.root.asMessageAudioEvent()?.duration }.sum()
    }

    fun hasUnableToDecryptEvent(): Boolean {
        return group.events.any { it.root.getClearType() == EventType.ENCRYPTED }
    }
}
