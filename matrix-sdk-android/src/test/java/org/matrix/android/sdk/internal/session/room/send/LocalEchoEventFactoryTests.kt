/*
 * Copyright (c) 2022 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.room.send

import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.internal.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.crypto.model.OlmDecryptionResult
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContentWithFormattedBody
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.PollType
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.util.TextContent
import org.matrix.android.sdk.test.fakes.FakeClock
import org.matrix.android.sdk.test.fakes.FakeContext
import org.matrix.android.sdk.test.fakes.internal.session.content.FakeThumbnailExtractor
import org.matrix.android.sdk.test.fakes.internal.session.permalinks.FakePermalinkFactory
import org.matrix.android.sdk.test.fakes.internal.session.room.send.FakeLocalEchoRepository
import org.matrix.android.sdk.test.fakes.internal.session.room.send.FakeMarkdownParser
import org.matrix.android.sdk.test.fakes.internal.session.room.send.FakeWaveFormSanitizer
import org.matrix.android.sdk.test.fakes.internal.session.room.send.pills.FakeTextPillsUtils

@Suppress("MaxLineLength")
class LocalEchoEventFactoryTests {

    companion object {
        internal const val A_USER_ID_1 = "@user_1:matrix.org"
        internal const val A_ROOM_ID = "!sUeOGZKsBValPTUMax:matrix.org"
        internal const val AN_EVENT_ID = "\$vApgexcL8Vfh-WxYKsFKCDooo67ttbjm3TiVKXaWijU"
        internal const val AN_EPOCH = 1655210176L
        internal const val A_MEDIA_URI = "content://media/1"

        val A_START_EVENT = Event(
                type = EventType.STATE_ROOM_CREATE,
                eventId = AN_EVENT_ID,
                originServerTs = 1652435922563,
                senderId = A_USER_ID_1,
                roomId = A_ROOM_ID
        )
    }

    private val fakeContext = FakeContext()
    private val fakeMarkdownParser = FakeMarkdownParser()
    private val fakeTextPillsUtils = FakeTextPillsUtils()
    private val fakeThumbnailExtractor = FakeThumbnailExtractor()
    private val fakeWaveFormSanitizer = FakeWaveFormSanitizer()
    private val fakeLocalEchoRepository = FakeLocalEchoRepository()
    private val fakePermalinkFactory = FakePermalinkFactory()
    private val fakeClock = FakeClock()

    private val localEchoEventFactory = LocalEchoEventFactory(
            // Nothing under test reads a video's size; the real one needs MediaMetadataRetriever.
            videoMetadataExtractor = VideoMetadataExtractor { 0 to 0 },
            audioMetadataExtractor = AudioMetadataExtractor { null },
            userId = A_USER_ID_1,
            markdownParser = fakeMarkdownParser.instance,
            textPillsUtils = fakeTextPillsUtils.instance,
            thumbnailExtractor = fakeThumbnailExtractor.instance,
            waveformSanitizer = fakeWaveFormSanitizer.instance,
            localEchoRepository = fakeLocalEchoRepository.instance,
            permalinkFactory = fakePermalinkFactory.instance,
            clock = fakeClock,
            stateEventDataSource = mockk(),
    )

    @Test
    fun `poll writes use stable types and content even with an unstable kind`() {
        every { fakeLocalEchoRepository.instance.getEvent(A_ROOM_ID, AN_EVENT_ID) } returns Event(type = EventType.POLL_START.stable)
        val event = localEchoEventFactory.createPollEvent(A_ROOM_ID, PollType.DISCLOSED_UNSTABLE, "Lunch?", listOf("Pizza"))
        assertEquals("m.poll.start", event.type)
        val content = event.content!!
        assertTrue(content["m.text"] is List<*>)
        assertFalse(content.containsKey("org.matrix.msc3381.poll.start"))
        val poll = content["m.poll"] as Map<*, *>
        assertEquals("m.disclosed", poll["kind"])
        val answer = (poll["answers"] as List<*>).single() as Map<*, *>
        assertTrue(answer["m.id"] is String)
        assertTrue(answer["m.text"] is List<*>)
        assertFalse(answer.containsKey("id"))

        val edit = localEchoEventFactory.createPollReplaceEvent(A_ROOM_ID, PollType.UNDISCLOSED, AN_EVENT_ID, "Dinner?", listOf("Pizza"))
        assertEquals("m.poll.start", edit.type)
        val editedContent = edit.content!!["m.new_content"] as Map<*, *>
        assertEquals(edit.content!!["m.poll"], editedContent["m.poll"])

        val vote = localEchoEventFactory.createPollReplyEvent(A_ROOM_ID, AN_EVENT_ID, "pizza")
        assertEquals("m.poll.response", vote.type)
        assertEquals(listOf("pizza"), vote.content!!["m.selections"])
        assertFalse(vote.content!!.containsKey("org.matrix.msc3381.poll.response"))

        val end = localEchoEventFactory.createEndPollEvent(A_ROOM_ID, AN_EVENT_ID)
        assertEquals("m.poll.end", end.type)
        assertTrue(end.content!!["m.text"] is List<*>)
        assertFalse(end.content!!.containsKey("org.matrix.msc1767.text"))
    }

    @Test
    fun `votes closure and edits of unstable polls preserve the unstable format`() {
        every { fakeLocalEchoRepository.instance.getEvent(A_ROOM_ID, AN_EVENT_ID) } returns Event(type = EventType.POLL_START.unstable)
        assertUnstablePollResponses()
        val edit = localEchoEventFactory.createPollReplaceEvent(A_ROOM_ID, PollType.DISCLOSED, AN_EVENT_ID, "Dinner?", listOf("Pizza"))
        assertEquals(EventType.POLL_START.unstable, edit.type)
        val content = edit.content!!["m.new_content"] as Map<*, *>
        assertFalse(content.containsKey("m.poll"))
        val poll = content[EventType.POLL_START.unstable] as Map<*, *>
        assertEquals("org.matrix.msc3381.poll.disclosed", poll["kind"])
        val answer = (poll["answers"] as List<*>).single() as Map<*, *>
        assertTrue(answer["id"] is String)
        assertEquals("Pizza", answer["org.matrix.msc1767.text"])
        assertFalse(answer.containsKey("m.id"))
    }

    @Test
    fun `unknown poll targets use unstable responses`() {
        every { fakeLocalEchoRepository.instance.getEvent(A_ROOM_ID, AN_EVENT_ID) } returns null
        assertUnstablePollResponses()
    }

    @Test
    fun `encrypted poll responses use the decrypted event type`() {
        val poll = Event(type = EventType.ENCRYPTED).apply {
            mxDecryptionResult = OlmDecryptionResult(payload = mapOf("type" to EventType.POLL_START.unstable))
        }
        every { fakeLocalEchoRepository.instance.getEvent(A_ROOM_ID, AN_EVENT_ID) } returns poll
        assertUnstablePollResponses()
    }

    private fun assertUnstablePollResponses() {
        val vote = localEchoEventFactory.createPollReplyEvent(A_ROOM_ID, AN_EVENT_ID, "pizza")
        assertEquals(EventType.POLL_RESPONSE.unstable, vote.type)
        assertFalse(vote.content!!.containsKey("m.selections"))
        assertEquals(mapOf("answers" to listOf("pizza")), vote.content!![EventType.POLL_RESPONSE.unstable])
        val end = localEchoEventFactory.createEndPollEvent(A_ROOM_ID, AN_EVENT_ID)
        assertEquals(EventType.POLL_END.unstable, end.type)
        assertFalse(end.content!!.containsKey("m.text"))
        assertEquals("Ended poll", end.content!!["org.matrix.msc1767.text"])
        assertEquals(emptyMap<String, Any>(), end.content!![EventType.POLL_END.unstable])
    }

    @Before
    fun setup() {
        fakeClock.givenEpoch(AN_EPOCH)
        fakeMarkdownParser.givenBoldMarkdown()
    }

    @Test
    fun `given a null quotedText, when a quote event is created, then the result message should only contain the new text after new lines`() {
        val event = createTimelineEvent(null, null)
        val quotedContent = localEchoEventFactory.createQuotedTextEvent(
                roomId = A_ROOM_ID,
                quotedEvent = event,
                text = "Text",
                formattedText = null,
                autoMarkdown = false,
                rootThreadEventId = null,
                additionalContent = null,
        ).content.toModel<MessageContent>()
        assertEquals("\n\nText", quotedContent?.body)
        assertEquals("<br/>Text", (quotedContent as? MessageContentWithFormattedBody)?.formattedBody)
    }

    @Test
    fun `given a plain text quoted message, when a quote event is created, then the result message should contain both the quoted and new text`() {
        val event = createTimelineEvent("Quoted", null)
        val quotedContent = localEchoEventFactory.createQuotedTextEvent(
                roomId = A_ROOM_ID,
                quotedEvent = event,
                text = "Text",
                formattedText = null,
                autoMarkdown = false,
                rootThreadEventId = null,
                additionalContent = null,
        ).content.toModel<MessageContent>()
        assertEquals("> Quoted\n\nText", quotedContent?.body)
        assertEquals("<blockquote>Quoted</blockquote><br/>Text", (quotedContent as? MessageContentWithFormattedBody)?.formattedBody)
    }

    @Test
    fun `given a formatted text quoted message, when a quote event is created, then the result message should contain both the formatted quote and new text`() {
        val event = createTimelineEvent("Quoted", "<b>Quoted</b>")
        val quotedContent = localEchoEventFactory.createQuotedTextEvent(
                roomId = A_ROOM_ID,
                quotedEvent = event,
                text = "Text",
                formattedText = null,
                autoMarkdown = false,
                rootThreadEventId = null,
                additionalContent = null,
        ).content.toModel<MessageContent>()
        // This still uses the plain text version
        assertEquals("> Quoted\n\nText", quotedContent?.body)
        // This one has the formatted one
        assertEquals("<blockquote><b>Quoted</b></blockquote><br/>Text", (quotedContent as? MessageContentWithFormattedBody)?.formattedBody)
    }

    @Test
    fun `given formatted text quoted message and new message, when a quote event is created, then the result message should contain both the formatted quote and new formatted text`() {
        val event = createTimelineEvent("Quoted", "<b>Quoted</b>")
        val quotedContent = localEchoEventFactory.createQuotedTextEvent(
                roomId = A_ROOM_ID,
                quotedEvent = event,
                text = "Text",
                formattedText = "<b>Formatted text</b>",
                autoMarkdown = false,
                rootThreadEventId = null,
                additionalContent = null,
        ).content.toModel<MessageContent>()
        // This still uses the plain text version
        assertEquals("> Quoted\n\nText", quotedContent?.body)
        // This one has the formatted one
        assertEquals(
                "<blockquote><b>Quoted</b></blockquote><br/><b>Formatted text</b>",
                (quotedContent as? MessageContentWithFormattedBody)?.formattedBody
        )
    }

    @Test
    fun `given formatted text quoted message and new message with autoMarkdown, when a quote event is created, then the result message should contain both the formatted quote and new formatted text, not the markdown processed text`() {
        val event = createTimelineEvent("Quoted", "<b>Quoted</b>")
        val quotedContent = localEchoEventFactory.createQuotedTextEvent(
                roomId = A_ROOM_ID,
                quotedEvent = event,
                text = "Text",
                formattedText = "<b>Formatted text</b>",
                autoMarkdown = true,
                rootThreadEventId = null,
                additionalContent = null,
        ).content.toModel<MessageContent>()
        // This still uses the plain text version
        assertEquals("> Quoted\n\nText", quotedContent?.body)
        // This one has the formatted one
        assertEquals(
                "<blockquote><b>Quoted</b></blockquote><br/><b>Formatted text</b>",
                (quotedContent as? MessageContentWithFormattedBody)?.formattedBody
        )
    }

    @Test
    fun `given a formatted text quoted message and a new message with autoMarkdown, when a quote event is created, then the result message should contain both the formatted quote and new processed formatted text`() {
        val event = createTimelineEvent("Quoted", "<b>Quoted</b>")
        val quotedContent = localEchoEventFactory.createQuotedTextEvent(
                roomId = A_ROOM_ID,
                quotedEvent = event,
                text = "**Text**",
                formattedText = null,
                autoMarkdown = true,
                rootThreadEventId = null,
                additionalContent = null,
        ).content.toModel<MessageContent>()
        // This still uses the markdown text version
        assertEquals("> Quoted\n\n**Text**", quotedContent?.body)
        // This one has the formatted one
        assertEquals(
                "<blockquote><b>Quoted</b></blockquote><br/><b>Text</b>",
                (quotedContent as? MessageContentWithFormattedBody)?.formattedBody
        )
    }

    @Test
    fun `given a plain text quoted message and a new message with autoMarkdown, when a quote event is created, then the result message should the plain text quote and new processed formatted text`() {
        val event = createTimelineEvent("Quoted", null)
        val quotedContent = localEchoEventFactory.createQuotedTextEvent(
                roomId = A_ROOM_ID,
                quotedEvent = event,
                text = "**Text**",
                formattedText = null,
                autoMarkdown = true,
                rootThreadEventId = null,
                additionalContent = null,
        ).content.toModel<MessageContent>()
        // This still uses the markdown text version
        assertEquals("> Quoted\n\n**Text**", quotedContent?.body)
        // This one has the formatted one
        assertEquals(
                "<blockquote>Quoted</blockquote><br/><b>Text</b>",
                (quotedContent as? MessageContentWithFormattedBody)?.formattedBody
        )
    }

    @Test
    fun `a markdown reply and its edit send quote blocks expanded in the plain body`() {
        every { fakeMarkdownParser.instance.expandQuoteFences("<<< hi") } returns "> hi"
        val replied = createTimelineEvent("Text", null)

        val reply = localEchoEventFactory.createReplyTextContent(replied, "<<< hi", null, autoMarkdown = true, showInThread = false)
        assertEquals("> hi", reply?.body)

        val edit = localEchoEventFactory.createReplaceTextOfReply(
                roomId = A_ROOM_ID,
                eventReplaced = replied,
                originalEvent = replied,
                replyText = "<<< hi",
                replyTextFormatted = null,
                autoMarkdown = true,
                msgType = MessageType.MSGTYPE_TEXT,
                compatibilityText = "* <<< hi",
        )
        assertEquals("> hi", edit.newContent()?.get("body"))
    }

    @Test
    fun `a reply keeps its plain body verbatim when markdown is off`() {
        every { fakeMarkdownParser.instance.expandQuoteFences("<<< hi") } returns "> hi"
        every { fakeTextPillsUtils.instance.processSpecialSpansToHtml(any()) } returns null
        val reply = localEchoEventFactory.createReplyTextContent(
                createTimelineEvent("Text", null), "<<< hi", null, autoMarkdown = false, showInThread = false
        )
        assertEquals("<<< hi", reply?.body)
    }

    @Test
    fun `a media edit replaces the message with the new media, and says which message it replaces`() {
        val edit = localEchoEventFactory.createMediaReplaceEvent(
                roomId = A_ROOM_ID,
                targetEvent = createTimelineEvent("Text", null),
                attachment = anImageAttachment(),
        )

        assertEquals(EventType.MESSAGE, edit.type)
        val relation = edit.content?.get("m.relates_to") as? Map<*, *>
        assertEquals(RelationType.REPLACE, relation?.get("rel_type"))
        assertEquals(AN_EVENT_ID, relation?.get("event_id"))
        val newContent = edit.newContent()
        assertEquals(MessageType.MSGTYPE_IMAGE, newContent.toModel<MessageContent>()?.msgType)
        assertEquals(A_MEDIA_URI, newContent?.get("url"))
        // The copy shown by clients which don't apply edits carries the same media.
        assertEquals(MessageType.MSGTYPE_IMAGE, edit.content.toModel<MessageContent>()?.msgType)
        assertEquals(A_MEDIA_URI, edit.content?.get("url"))
    }

    @Test
    fun `a media edit carries its caption, keeping the file name in filename`() {
        val edit = localEchoEventFactory.createMediaReplaceEvent(
                roomId = A_ROOM_ID,
                targetEvent = createTimelineEvent("Text", null),
                attachment = anImageAttachment(),
                captionText = "A caption",
        )

        val newContent = edit.newContent()
        assertEquals("A caption", newContent?.get("body"))
        assertEquals("image.jpg", newContent?.get("filename"))
    }

    @Test
    fun `replacing a sticker stays a sticker, which has no msgtype`() {
        val sticker = createTimelineEvent(null, null).let { it.copy(root = it.root.copy(type = EventType.STICKER)) }

        val edit = localEchoEventFactory.createMediaReplaceEvent(
                roomId = A_ROOM_ID,
                targetEvent = sticker,
                attachment = anImageAttachment(),
        )

        assertEquals(EventType.STICKER, edit.type)
        assertEquals(null, edit.content?.get(MessageContent.MSG_TYPE_JSON_KEY))
        val newContent = edit.newContent()
        assertEquals(null, newContent?.get(MessageContent.MSG_TYPE_JSON_KEY))
        assertEquals(A_MEDIA_URI, newContent?.get("url"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun Event.newContent(): Content? = content?.get("m.new_content") as? Content

    private fun anImageAttachment() = ContentAttachmentData(
            size = 1024,
            name = "image.jpg",
            queryUri = A_MEDIA_URI,
            mimeType = "image/jpeg",
            type = ContentAttachmentData.Type.IMAGE,
    )

    private fun createTimelineEvent(quotedText: String?, formattedQuotedText: String?): TimelineEvent {
        val textContent = quotedText?.let {
            TextContent(
                    quotedText,
                    formattedQuotedText
            ).toMessageTextContent().toContent()
        }
        return TimelineEvent(
                root = A_START_EVENT.copy(
                        type = EventType.MESSAGE,
                        content = textContent
                ),
                localId = 1234,
                eventId = AN_EVENT_ID,
                senderInfo = SenderInfo(A_USER_ID_1, A_USER_ID_1, true, null),
        )
    }
}
