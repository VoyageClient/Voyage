/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.model.create

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.state.StateService

/**
 * Content of a MSC3946 `m.room.predecessor` state event, which links a room to its predecessor
 * after the fact rather than at creation time.
 */
@JsonClass(generateAdapter = true)
data class RoomPredecessorContent(
        @Json(name = "predecessor_room_id") val predecessorRoomId: String? = null,
        @Json(name = "last_known_event_id") val lastKnownEventId: String? = null,
        @Json(name = "via_servers") val viaServers: List<String>? = null,
)

object RoomPredecessors {

    /**
     * Resolves the room this one continues from. A MSC3946 predecessor state event wins over the
     * `predecessor` field of `m.room.create`, since it can be added (and corrected) later.
     */
    fun resolve(predecessorStateEvents: List<Event>, createEvent: Event?): Predecessor? {
        val dynamic = predecessorStateEvents.firstOrNull { it.type == EventType.STATE_ROOM_PREDECESSOR.stable }
                ?: predecessorStateEvents.firstOrNull { it.type == EventType.STATE_ROOM_PREDECESSOR.unstable }
        dynamic?.toPredecessor()?.let { return it }
        return createEvent?.getClearContent().toModel<RoomCreateContent>()?.predecessor?.takeIf { !it.roomId.isNullOrEmpty() }
    }

    fun Event.toPredecessor(): Predecessor? {
        val content = getClearContent().toModel<RoomPredecessorContent>() ?: return null
        val roomId = content.predecessorRoomId?.takeIf { it.isNotEmpty() } ?: return null
        return Predecessor(roomId = roomId, eventId = content.lastKnownEventId?.takeIf { it.isNotEmpty() })
    }
}

fun StateService.findPredecessor(): Predecessor? {
    return RoomPredecessors.resolve(
            predecessorStateEvents = getStateEvents(EventType.STATE_ROOM_PREDECESSOR.values.toSet(), QueryStringValue.IsEmpty),
            createEvent = getStateEvent(EventType.STATE_ROOM_CREATE, QueryStringValue.IsEmpty),
    )
}
