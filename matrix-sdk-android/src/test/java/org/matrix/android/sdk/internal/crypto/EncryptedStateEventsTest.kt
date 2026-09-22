/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType

class EncryptedStateEventsTest {

    @Test
    fun `packed state key is type and state key joined by a colon`() {
        EncryptedStateEvents.packStateKey("m.room.name", "") shouldBeEqualTo "m.room.name:"
        EncryptedStateEvents.packStateKey("m.space.child", "!room:example.org") shouldBeEqualTo "m.space.child:!room:example.org"
    }

    @Test
    fun `an undecrypted encrypted state event takes the slot its plaintext packed key names`() {
        val event = Event(type = EventType.ENCRYPTED, stateKey = "m.room.name:", roomId = "!r:hs")

        EncryptedStateEvents.stateSlot(event) shouldBeEqualTo ("m.room.name" to "")
    }

    @Test
    fun `a packed key claiming protocol-critical state is not unpacked`() {
        // Otherwise anyone able to send state could shadow a membership with an unreadable blob.
        val event = Event(type = EventType.ENCRYPTED, stateKey = "m.room.member:@alice:example.org", roomId = "!r:hs")

        EncryptedStateEvents.stateSlot(event) shouldBeEqualTo (EventType.ENCRYPTED to "m.room.member:@alice:example.org")
    }

    @Test
    fun `a packed key without a colon stays on the wire type`() {
        val event = Event(type = EventType.ENCRYPTED, stateKey = "nonsense", roomId = "!r:hs")

        EncryptedStateEvents.stateSlot(event) shouldBeEqualTo (EventType.ENCRYPTED to "nonsense")
    }

    @Test
    fun `an unencrypted state event keeps its own type and state key`() {
        val event = Event(type = "m.room.topic", stateKey = "", roomId = "!r:hs")

        EncryptedStateEvents.stateSlot(event) shouldBeEqualTo ("m.room.topic" to "")
    }

    @Test
    fun `a message event occupies no state slot`() {
        EncryptedStateEvents.stateSlot(Event(type = EventType.MESSAGE, roomId = "!r:hs")).shouldBeNull()
    }

    @Test
    fun `decrypting names the state it makes stale`() {
        EncryptedStateEvents.refreshFor(EventType.STATE_ROOM_NAME) shouldBe EncryptedStateEvents.Refresh.ROOM_SUMMARY
        EncryptedStateEvents.refreshFor(EventType.STATE_ROOM_AVATAR) shouldBe EncryptedStateEvents.Refresh.ROOM_SUMMARY
        // The graph is built from via servers and order, which stay unreadable until this decrypts.
        EncryptedStateEvents.refreshFor(EventType.STATE_SPACE_CHILD) shouldBe EncryptedStateEvents.Refresh.SPACE_GRAPH
        EncryptedStateEvents.refreshFor(EventType.STATE_SPACE_PARENT) shouldBe EncryptedStateEvents.Refresh.SPACE_GRAPH
        EncryptedStateEvents.refreshFor("m.room.pinned_events") shouldBe EncryptedStateEvents.Refresh.NONE
    }

    @Test
    fun `a packed state key matching the decrypted event is valid`() {
        EncryptedStateEvents.isPackedStateKeyValid(
                "m.room.topic:",
                mapOf("type" to "m.room.topic", "state_key" to "", "content" to emptyMap<String, Any>())
        ) shouldBe true
    }

    @Test
    fun `a state key containing colons is matched in full`() {
        EncryptedStateEvents.isPackedStateKeyValid(
                "m.space.child:!room:example.org",
                mapOf("type" to "m.space.child", "state_key" to "!room:example.org")
        ) shouldBe true
    }

    @Test
    fun `a mismatched type or state key is rejected`() {
        EncryptedStateEvents.isPackedStateKeyValid(
                "m.room.topic:",
                mapOf("type" to "m.room.name", "state_key" to "")
        ) shouldBe false
        EncryptedStateEvents.isPackedStateKeyValid(
                "m.space.child:!room:example.org",
                mapOf("type" to "m.space.child", "state_key" to "!other:example.org")
        ) shouldBe false
    }

    @Test
    fun `state and message events cannot masquerade as each other`() {
        // A packed state key over a decrypted message event.
        EncryptedStateEvents.isPackedStateKeyValid(
                "m.room.message:",
                mapOf("type" to "m.room.message")
        ) shouldBe false
        // A decrypted state event delivered without a packed state key.
        EncryptedStateEvents.isPackedStateKeyValid(
                null,
                mapOf("type" to "m.room.name", "state_key" to "")
        ) shouldBe false
    }

    @Test
    fun `an encrypted event claiming protocol-critical state is rejected`() {
        // It decrypts cleanly and its packed key round-trips, but such state is never encrypted, so
        // honouring it would shadow the membership the server actually resolved.
        EncryptedStateEvents.isPackedStateKeyValid(
                "m.room.member:@alice:example.org",
                mapOf("type" to "m.room.member", "state_key" to "@alice:example.org")
        ) shouldBe false
    }

    @Test
    fun `a message event without a state key is valid`() {
        EncryptedStateEvents.isPackedStateKeyValid(
                null,
                mapOf("type" to "m.room.message", "content" to emptyMap<String, Any>())
        ) shouldBe true
    }
}
