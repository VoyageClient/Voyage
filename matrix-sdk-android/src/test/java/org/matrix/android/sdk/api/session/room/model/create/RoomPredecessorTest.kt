/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.model.create

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType

class RoomPredecessorTest {

    private fun createEvent(predecessorRoomId: String?, eventId: String? = "\$tombstone") = Event(
            type = EventType.STATE_ROOM_CREATE,
            stateKey = "",
            content = buildMap {
                put("room_version", "10")
                predecessorRoomId?.let {
                    put("predecessor", buildMap {
                        put("room_id", it)
                        eventId?.let { id -> put("event_id", id) }
                    })
                }
            },
    )

    private fun predecessorEvent(
            type: String = "m.room.predecessor",
            roomId: String? = "!dynamic:example.org",
            lastKnownEventId: String? = "\$last",
    ) = Event(
            type = type,
            stateKey = "",
            content = buildMap {
                roomId?.let { put("predecessor_room_id", it) }
                lastKnownEventId?.let { put("last_known_event_id", it) }
            },
    )

    @Test
    fun `falls back to the create event predecessor`() {
        val predecessor = RoomPredecessors.resolve(emptyList(), createEvent("!old:example.org"))!!

        predecessor.roomId shouldBeEqualTo "!old:example.org"
        predecessor.eventId shouldBeEqualTo "\$tombstone"
    }

    @Test
    fun `a dynamic predecessor wins over the create event one`() {
        val predecessor = RoomPredecessors.resolve(listOf(predecessorEvent()), createEvent("!old:example.org"))!!

        predecessor.roomId shouldBeEqualTo "!dynamic:example.org"
        predecessor.eventId shouldBeEqualTo "\$last"
    }

    @Test
    fun `the stable predecessor type wins over the unstable one`() {
        val events = listOf(
                predecessorEvent(type = "org.matrix.msc3946.room_predecessor", roomId = "!unstable:example.org"),
                predecessorEvent(type = "m.room.predecessor", roomId = "!stable:example.org"),
        )

        RoomPredecessors.resolve(events, null)!!.roomId shouldBeEqualTo "!stable:example.org"
    }

    @Test
    fun `the unstable predecessor type is used when the stable one is absent`() {
        val events = listOf(predecessorEvent(type = "org.matrix.msc3946.room_predecessor", roomId = "!unstable:example.org"))

        RoomPredecessors.resolve(events, null)!!.roomId shouldBeEqualTo "!unstable:example.org"
    }

    @Test
    fun `an unusable dynamic predecessor falls through to the create event`() {
        // A cleared predecessor event must not shadow a still-valid create-content predecessor.
        val events = listOf(predecessorEvent(roomId = null, lastKnownEventId = null))

        RoomPredecessors.resolve(events, createEvent("!old:example.org"))!!.roomId shouldBeEqualTo "!old:example.org"
    }

    @Test
    fun `a dynamic predecessor without a last known event id still resolves`() {
        val predecessor = RoomPredecessors.resolve(listOf(predecessorEvent(lastKnownEventId = null)), null)!!

        predecessor.roomId shouldBeEqualTo "!dynamic:example.org"
        predecessor.eventId.shouldBeNull()
    }

    @Test
    fun `no predecessor anywhere resolves to null`() {
        RoomPredecessors.resolve(emptyList(), createEvent(null)).shouldBeNull()
        RoomPredecessors.resolve(emptyList(), null).shouldBeNull()
    }
}
