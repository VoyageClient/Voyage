/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent

class PerMessageProfileRenderingTest {

    @Test
    fun `given no fallback display name then the body is untouched`() {
        "Alice: hello".withoutPerMessageProfileFallback(null) shouldBeEqualTo "Alice: hello"
    }

    @Test
    fun `given a plaintext fallback prefix then it is stripped`() {
        "Alice: hello".withoutPerMessageProfileFallback("Alice") shouldBeEqualTo "hello"
    }

    @Test
    fun `given a plaintext fallback for another name then the body is untouched`() {
        "Alice: hello".withoutPerMessageProfileFallback("Bob") shouldBeEqualTo "Alice: hello"
    }

    @Test
    fun `given a mid-body name mention then it is not stripped`() {
        "say Alice: hello".withoutPerMessageProfileFallback("Alice") shouldBeEqualTo "say Alice: hello"
    }

    @Test
    fun `given an html fallback then the marker element is stripped`() {
        """<strong data-mx-profile-fallback>Alice: </strong>hello"""
                .withoutPerMessageProfileFallback("Alice") shouldBeEqualTo "hello"
    }

    @Test
    fun `given an html fallback with an empty attribute value then the marker element is stripped`() {
        """<strong data-mx-profile-fallback="">Alice: </strong >hello"""
                .withoutPerMessageProfileFallback("Alice") shouldBeEqualTo "hello"
    }

    @Test
    fun `given a missing event then it has no sender identity`() {
        null.perMessageSenderIdentity(enabled = true) shouldBe null
    }

    @Test
    fun `given two messages under the same profile then they share a sender identity`() {
        val first = anEvent(profileId = "bob", displayName = "Bob")
        val second = anEvent(profileId = "bob", displayName = "Bob")

        first.perMessageSenderIdentity(enabled = true) shouldBeEqualTo second.perMessageSenderIdentity(enabled = true)
    }

    @Test
    fun `given the same sender under two profiles then their sender identities differ`() {
        val bob = anEvent(profileId = "bob", displayName = "Bob")
        val alice = anEvent(profileId = "alice", displayName = "Alice")

        bob.perMessageSenderIdentity(enabled = true) shouldNotBeEqualTo alice.perMessageSenderIdentity(enabled = true)
    }

    @Test
    fun `given profiles are disabled then the same sender keeps one identity`() {
        val bob = anEvent(profileId = "bob", displayName = "Bob")
        val alice = anEvent(profileId = "alice", displayName = "Alice")

        bob.perMessageSenderIdentity(enabled = false) shouldBeEqualTo alice.perMessageSenderIdentity(enabled = false)
    }

    @Test
    fun `given a profile clearing the avatar then the sender identity drops the member avatar`() {
        val cleared = anEvent(profileId = "bob", displayName = "Bob", avatarUrl = "")

        cleared.perMessageSenderIdentity(enabled = true)?.last() shouldBe null
    }

    private fun anEvent(profileId: String, displayName: String, avatarUrl: String = "mxc://example.org/profile"): TimelineEvent {
        val root = Event(
                type = EventType.MESSAGE,
                eventId = "\$event",
                roomId = "!room:example.org",
                senderId = "@bridge:example.org",
                content = mapOf(
                        "msgtype" to "m.text",
                        "body" to "Hello",
                        "m.per_message_profile" to mapOf(
                                "id" to profileId,
                                "displayname" to displayName,
                                "avatar_url" to avatarUrl,
                        ),
                ),
        )
        return TimelineEvent(
                root = root,
                localId = 1L,
                eventId = "\$event",
                senderInfo = SenderInfo("@bridge:example.org", "Bridge", true, "mxc://example.org/member"),
        )
    }
}
