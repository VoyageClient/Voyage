/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import im.vector.app.features.home.room.detail.timeline.STATE_ROOM_VOICE_BROADCAST_INFO
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent

object TimelineDisplayableEvents {

    /**
     * All types we have an item to build with. Every type not defined here will be shown as DefaultItem if forced to be shown, otherwise will be hidden.
     */
    val DISPLAYABLE_TYPES: List<String> = listOf(
            EventType.MESSAGE,
            EventType.STATE_ROOM_WIDGET_LEGACY,
            EventType.STATE_ROOM_WIDGET,
            EventType.STATE_ROOM_NAME,
            EventType.STATE_ROOM_TOPIC,
            EventType.STATE_ROOM_AVATAR,
            EventType.STATE_ROOM_MEMBER,
            EventType.STATE_ROOM_ALIASES,
            EventType.STATE_ROOM_CANONICAL_ALIAS,
            EventType.STATE_ROOM_HISTORY_VISIBILITY,
            EventType.STATE_ROOM_SERVER_ACL,
            EventType.STATE_ROOM_POWER_LEVELS,
            EventType.STATE_ROOM_IMAGE_PACK,
            EventType.STATE_ROOM_IMAGE_PACK_UNSTABLE,
            EventType.CALL_INVITE,
            EventType.CALL_HANGUP,
            EventType.CALL_ANSWER,
            EventType.CALL_REJECT,
            EventType.ENCRYPTED,
            EventType.STATE_ROOM_ENCRYPTION,
            EventType.STATE_ROOM_GUEST_ACCESS,
            EventType.STATE_ROOM_THIRD_PARTY_INVITE,
            EventType.STICKER,
            EventType.STATE_ROOM_CREATE,
            EventType.STATE_ROOM_TOMBSTONE,
            EventType.STATE_ROOM_JOIN_RULES,
            EventType.KEY_VERIFICATION_DONE,
            EventType.KEY_VERIFICATION_CANCEL,
            STATE_ROOM_VOICE_BROADCAST_INFO,
    ) +
            EventType.POLL_START.values +
            EventType.POLL_END.values +
            EventType.ELEMENT_CALL_NOTIFY.values +
            EventType.STATE_ROOM_BEACON_INFO.values +
            EventType.BEACON_LOCATION_DATA.values
}

/**
 * A key that stays constant across the local-echo → synced-event swap, so the list rebinds the
 * bubble in place instead of removing and re-inserting it (which flashes the message out for a beat).
 * A local echo and the event the homeserver echoes back share the same transaction id; every other
 * event falls back to its event id.
 */
fun TimelineEvent.timelineStableId(): String {
    return root.unsignedData?.transactionId?.takeIf { it.isNotEmpty() } ?: eventId
}

/**
 * The type used to decide whether consecutive events merge into one summary. The stable and legacy image
 * pack ids collapse to a single key so a mix of m.room.image_pack / im.ponies.room_emotes events still
 * groups together as one "image pack changes" summary.
 */
fun Event.timelineMergeGroupType(): String = when (getClearType()) {
    EventType.STATE_ROOM_IMAGE_PACK,
    EventType.STATE_ROOM_IMAGE_PACK_UNSTABLE -> EventType.STATE_ROOM_IMAGE_PACK
    else -> getClearType()
}

fun TimelineEvent.isRoomConfiguration(roomCreatorUserId: String?): Boolean {
    return root.isStateEvent() && when (root.getClearType()) {
        EventType.STATE_ROOM_GUEST_ACCESS,
        EventType.STATE_ROOM_HISTORY_VISIBILITY,
        EventType.STATE_ROOM_JOIN_RULES,
        EventType.STATE_ROOM_NAME,
        EventType.STATE_ROOM_TOPIC,
        EventType.STATE_ROOM_AVATAR,
        EventType.STATE_ROOM_ALIASES,
        EventType.STATE_ROOM_CANONICAL_ALIAS,
        EventType.STATE_ROOM_POWER_LEVELS,
        EventType.STATE_ROOM_ENCRYPTION -> true
        EventType.STATE_ROOM_MEMBER -> {
            // Keep only room member events regarding the room creator (when he joined the room),
            // but exclude events where the room creator invite others, or where others join
            roomCreatorUserId != null && root.stateKey == roomCreatorUserId
        }
        else -> false
    }
}
