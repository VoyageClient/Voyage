/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.model.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.LocalEcho

class ForwardedInfoTest {

    private fun event(
            eventId: String = "\$event",
            roomId: String? = "!room:example.org",
            senderId: String? = "@alice:example.org",
            content: Map<String, Any> = mapOf("msgtype" to "m.image", "body" to "cat.png")
    ) = Event(
            type = EventType.MESSAGE,
            eventId = eventId,
            roomId = roomId,
            senderId = senderId,
            originServerTs = 1234L,
            content = content
    )

    @Test
    fun `a sent event points back at itself under both keys`() {
        val info = event().toForwardedInfoContent()

        val expected = mapOf(
                "event_id" to "\$event",
                "room_id" to "!room:example.org",
                "sender" to "@alice:example.org",
                "origin_server_ts" to 1234L
        )
        assertEquals(expected, info[ForwardedInfo.STABLE_KEY])
        assertEquals(expected, info[ForwardedInfo.UNSTABLE_KEY])
    }

    @Test
    fun `forwarding a forward keeps the original source`() {
        val source = mapOf(
                "event_id" to "\$origin",
                "room_id" to "!other:example.org",
                "sender" to "@bob:example.org",
                "origin_server_ts" to 10L
        )
        val info = event(content = mapOf("msgtype" to "m.image", ForwardedInfo.STABLE_KEY to source)).toForwardedInfoContent()

        assertEquals(source, info[ForwardedInfo.STABLE_KEY])
        assertEquals(source, info[ForwardedInfo.UNSTABLE_KEY])
    }

    @Test
    fun `an event with no server identity carries no info`() {
        assertTrue(event(eventId = LocalEcho.createLocalEchoId()).toForwardedInfoContent().isEmpty())
        assertTrue(event(roomId = null).toForwardedInfoContent().isEmpty())
        assertTrue(event(senderId = null).toForwardedInfoContent().isEmpty())
    }
}
