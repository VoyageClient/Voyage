/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.timeline

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.EditAggregatedSummary
import org.matrix.android.sdk.api.session.room.model.EventAnnotationsSummary
import org.matrix.android.sdk.api.session.room.model.message.MessageImageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageTextContent
import org.matrix.android.sdk.api.session.room.model.message.getCaption
import org.matrix.android.sdk.api.session.room.model.message.getFileName
import org.matrix.android.sdk.api.session.room.sender.SenderInfo

class ReplyEditContentTest {

    private val replyRelation = mapOf("m.in_reply_to" to mapOf("event_id" to "\$replied"))

    private val image = mapOf(
            "msgtype" to "m.image",
            "url" to "mxc://example.org/image",
            "info" to mapOf("mimetype" to "image/png", "w" to 640, "h" to 480),
    )

    @Test
    fun `an edited captioned image reply keeps its media fields and reply relation`() {
        val event = anEvent(
                content = image + mapOf("body" to "photo.png", "m.relates_to" to replyRelation),
                newContent = image + mapOf("body" to "A sample caption", "filename" to "photo.png"),
        )

        val content = event.getLastMessageContent()

        content.shouldBeInstanceOf<MessageImageContent>()
        content as MessageImageContent
        content.url shouldBeEqualTo "mxc://example.org/image"
        content.getFileName() shouldBeEqualTo "photo.png"
        content.getCaption(isReply = true) shouldBeEqualTo "A sample caption"
        content.relatesTo?.inReplyTo?.eventId shouldBeEqualTo "\$replied"
    }

    @Test
    fun `an edited text reply keeps its reply relation`() {
        val event = anEvent(
                content = mapOf("msgtype" to "m.text", "body" to "Hello", "m.relates_to" to replyRelation),
                newContent = mapOf("msgtype" to "m.text", "body" to "Hello again"),
        )

        val content = event.getLastMessageContent() as MessageTextContent

        content.body shouldBeEqualTo "Hello again"
        content.relatesTo?.inReplyTo?.eventId shouldBeEqualTo "\$replied"
    }

    private fun anEvent(content: Map<String, Any>, newContent: Map<String, Any>) = TimelineEvent(
            root = Event(
                    type = EventType.MESSAGE,
                    eventId = "\$event",
                    roomId = "!room:example.org",
                    senderId = "@alice:example.org",
                    content = content,
            ),
            localId = 1L,
            eventId = "\$event",
            senderInfo = SenderInfo("@alice:example.org", "Alice", true, null),
            annotations = EventAnnotationsSummary(
                    editSummary = EditAggregatedSummary(
                            latestEdit = Event(
                                    type = EventType.MESSAGE,
                                    eventId = "\$edit",
                                    roomId = "!room:example.org",
                                    senderId = "@alice:example.org",
                                    content = newContent + mapOf(
                                            "body" to "* ${newContent["body"]}",
                                            "m.new_content" to newContent,
                                            "m.relates_to" to mapOf("rel_type" to "m.replace", "event_id" to "\$event"),
                                    ),
                            ),
                            sourceEvents = listOf("\$edit"),
                            localEchos = emptyList(),
                    ),
            ),
    )
}
