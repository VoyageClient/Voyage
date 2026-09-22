/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.timeline

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.api.session.room.model.EditAggregatedSummary
import org.matrix.android.sdk.api.session.room.model.EventAnnotationsSummary
import org.matrix.android.sdk.api.session.room.sender.SenderInfo

class PerMessageProfileTest {

    private val originalProfile = mapOf(
            "id" to "bob",
            "displayname" to "Bob",
            "avatar_url" to "mxc://example.org/original",
    )

    private val editedProfile = mapOf(
            "id" to "alice",
            "displayname" to "Alice",
            "avatar_url" to "mxc://example.org/edited",
    )

    @Test
    fun `given no edit then the original profile is used`() {
        val profile = anEvent().getPerMessageProfile()

        profile?.displayName shouldBeEqualTo "Bob"
        profile?.avatarUrl shouldBeEqualTo "mxc://example.org/original"
    }

    @Test
    fun `given an edit restating the profile in new content then the edited profile is used`() {
        val profile = anEvent(editContent = anEditContent(newContentProfile = editedProfile)).getPerMessageProfile()

        profile?.displayName shouldBeEqualTo "Alice"
        profile?.avatarUrl shouldBeEqualTo "mxc://example.org/edited"
    }

    @Test
    fun `given an edit carrying the profile only at its top level then the edited profile is used`() {
        val profile = anEvent(editContent = anEditContent(topLevelProfile = editedProfile)).getPerMessageProfile()

        profile?.displayName shouldBeEqualTo "Alice"
    }

    @Test
    fun `given an edit carrying no profile then the original profile still stands`() {
        val profile = anEvent(editContent = anEditContent()).getPerMessageProfile()

        profile?.displayName shouldBeEqualTo "Bob"
    }

    @Test
    fun `given an edited reply then the edited profile survives the reply reconstruction`() {
        val event = anEvent(
                relatesTo = mapOf("m.in_reply_to" to mapOf("event_id" to "\$replied")),
                editContent = anEditContent(newContentProfile = editedProfile),
        )

        event.getPerMessageProfile()?.displayName shouldBeEqualTo "Alice"
    }

    @Test
    fun `given an edited sticker then the edited profile is used`() {
        val event = anEvent(
                type = EventType.STICKER,
                editContent = anEditContent(newContentProfile = editedProfile),
        )

        event.getPerMessageProfile()?.displayName shouldBeEqualTo "Alice"
    }

    @Test
    fun `given the unstable profile key then it is read`() {
        val event = anEvent(content = mapOf(
                "msgtype" to "m.text",
                "body" to "Hello",
                "com.beeper.per_message_profile" to originalProfile,
        ))

        event.getPerMessageProfile()?.displayName shouldBeEqualTo "Bob"
    }

    @Test
    fun `given a redacted event then there is no profile`() {
        val event = anEvent(editContent = anEditContent(newContentProfile = editedProfile), redacted = true)

        event.getPerMessageProfile() shouldBe null
    }

    private fun anEvent(
            type: String = EventType.MESSAGE,
            content: Map<String, Any> = mapOf(
                    "msgtype" to "m.text",
                    "body" to "Hello",
                    "m.per_message_profile" to originalProfile,
            ),
            relatesTo: Map<String, Any>? = null,
            editContent: Map<String, Any>? = null,
            redacted: Boolean = false,
    ): TimelineEvent {
        val root = Event(
                type = type,
                eventId = "\$event",
                roomId = "!room:example.org",
                senderId = "@bridge:example.org",
                content = content + (relatesTo?.let { mapOf("m.relates_to" to it) } ?: emptyMap()),
                unsignedData = if (redacted) {
                    UnsignedData(age = 0, redactedEvent = Event(type = EventType.REDACTION, eventId = "\$redaction"))
                } else {
                    null
                },
        )
        return TimelineEvent(
                root = root,
                localId = 1L,
                eventId = "\$event",
                senderInfo = SenderInfo("@bridge:example.org", "Bridge", true, null),
                annotations = editContent?.let {
                    EventAnnotationsSummary(
                            editSummary = EditAggregatedSummary(
                                    latestEdit = Event(
                                            type = type,
                                            eventId = "\$edit",
                                            roomId = "!room:example.org",
                                            senderId = "@bridge:example.org",
                                            content = it,
                                    ),
                                    sourceEvents = listOf("\$edit"),
                                    localEchos = emptyList(),
                            )
                    )
                },
        )
    }

    private fun anEditContent(
            newContentProfile: Map<String, Any>? = null,
            topLevelProfile: Map<String, Any>? = null,
    ): Map<String, Any> {
        val newContent = mutableMapOf<String, Any>("msgtype" to "m.text", "body" to "Edited")
        newContentProfile?.let { newContent["m.per_message_profile"] = it }
        val content = mutableMapOf<String, Any>(
                "msgtype" to "m.text",
                "body" to "* Edited",
                "m.new_content" to newContent,
                "m.relates_to" to mapOf("rel_type" to "m.replace", "event_id" to "\$event"),
        )
        topLevelProfile?.let { content["m.per_message_profile"] = it }
        return content
    }
}
