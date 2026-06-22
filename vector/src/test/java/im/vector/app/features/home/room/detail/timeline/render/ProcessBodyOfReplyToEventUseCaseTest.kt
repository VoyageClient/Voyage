/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.render

import im.vector.app.core.resources.ColorProvider
import im.vector.app.features.home.room.detail.timeline.format.NoticeEventFormatter
import im.vector.app.test.fakes.FakeActiveSessionHolder
import im.vector.app.test.fakes.FakeStringProvider
import im.vector.lib.strings.CommonStrings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.getPollQuestion
import org.matrix.android.sdk.api.session.events.model.getRelationContent
import org.matrix.android.sdk.api.session.events.model.isAudioMessage
import org.matrix.android.sdk.api.session.events.model.isFileMessage
import org.matrix.android.sdk.api.session.events.model.isImageMessage
import org.matrix.android.sdk.api.session.events.model.isLiveLocation
import org.matrix.android.sdk.api.session.events.model.isPollEnd
import org.matrix.android.sdk.api.session.events.model.isPollStart
import org.matrix.android.sdk.api.session.events.model.isRedacted
import org.matrix.android.sdk.api.session.events.model.isReply
import org.matrix.android.sdk.api.session.events.model.isSticker
import org.matrix.android.sdk.api.session.events.model.isVideoMessage
import org.matrix.android.sdk.api.session.events.model.isVoiceMessage
import org.matrix.android.sdk.api.session.room.model.message.MessagePollContent
import org.matrix.android.sdk.api.session.room.model.message.MessageTextContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.PollCreationInfo
import org.matrix.android.sdk.api.session.room.model.message.PollQuestion
import org.matrix.android.sdk.api.session.room.model.relation.RelationDefaultContent
import org.matrix.android.sdk.api.session.room.model.relation.ReplyToContent
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getLastMessageContent

private const val A_ROOM_ID = "room-id"
private const val AN_EVENT_ID = "event-id"
private const val A_SENDER_ID = "@sender:matrix.org"
private const val A_PREFIX = "new-prefix"
private const val A_PREVIEW = "new-content"
private const val A_REPLY_TO_EVENT_MATRIX_FORMATTED_BODY =
        "<mx-reply>" +
                "<blockquote>" +
                "<a href=\"matrixToLink\">In reply to</a> " +
                "<a href=\"matrixToLink\">@user:matrix.org</a>" +
                "<br />" +
                "Message content" +
                "</blockquote>" +
                "</mx-reply>" +
                "Reply text"

// The use case always rebuilds the <mx-reply> block from our own cached event data, so the
// expected output is the synthetic block (permalink prefix + sender anchor + preview) followed by
// the original body with its embedded mx-reply stripped.
private fun expectedBody(preview: String) =
        "<mx-reply><blockquote>" +
                "<a href=\"https://matrix.to/#/$A_ROOM_ID/$AN_EVENT_ID\">$A_PREFIX</a>" +
                " <a href=\"https://matrix.to/#/$A_SENDER_ID\">$A_SENDER_ID</a>" +
                "<br />$preview" +
                "</blockquote></mx-reply>" +
                "Reply text"

class ProcessBodyOfReplyToEventUseCaseTest {

    private val fakeActiveSessionHolder = FakeActiveSessionHolder()
    private val fakeStringProvider = FakeStringProvider()
    private val fakeReplyToContent = ReplyToContent(eventId = AN_EVENT_ID)

    private val fakeNoticeEventFormatter = mockk<NoticeEventFormatter>(relaxed = true)
    private val fakeColorProvider = mockk<ColorProvider>(relaxed = true)

    private val processBodyOfReplyToEventUseCase = ProcessBodyOfReplyToEventUseCase(
            activeSessionHolder = fakeActiveSessionHolder.instance,
            stringProvider = fakeStringProvider.instance,
            noticeEventFormatter = fakeNoticeEventFormatter,
            colorProvider = fakeColorProvider,
    )

    private lateinit var fakeRepliedEvent: Event
    private lateinit var fakeTimelineEvent: TimelineEvent

    @Before
    fun setup() {
        mockkStatic("org.matrix.android.sdk.api.session.events.model.EventKt")
        mockkStatic("org.matrix.android.sdk.api.session.room.timeline.TimelineEventKt")
        fakeStringProvider.given(CommonStrings.message_reply_to_prefix, A_PREFIX)

        fakeRepliedEvent = mockk {
            every { eventId } returns AN_EVENT_ID
            every { roomId } returns A_ROOM_ID
            every { senderId } returns A_SENDER_ID
            every { isRedacted() } returns false
            every { getClearType() } returns EventType.MESSAGE
            every { isFileMessage() } returns false
            every { isVoiceMessage() } returns false
            every { isAudioMessage() } returns false
            every { isImageMessage() } returns false
            every { isVideoMessage() } returns false
            every { isSticker() } returns false
            every { isPollEnd() } returns false
            every { isPollStart() } returns false
            every { isLiveLocation() } returns false
            every { isReply() } returns false
        }
        fakeTimelineEvent = mockk {
            every { root } returns fakeRepliedEvent
            every { getLastMessageContent() } returns null
        }
        givenTimelineEventReturns(AN_EVENT_ID, fakeTimelineEvent)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `given a file message reply, the preview is the localized file stub`() {
        every { fakeRepliedEvent.isFileMessage() } returns true
        fakeStringProvider.given(CommonStrings.message_reply_to_sender_sent_file, A_PREVIEW)

        execute() shouldBeEqualTo expectedBody(A_PREVIEW)
    }

    @Test
    fun `given a voice message reply, the preview is the localized voice stub`() {
        every { fakeRepliedEvent.isVoiceMessage() } returns true
        fakeStringProvider.given(CommonStrings.message_reply_to_sender_sent_voice_message, A_PREVIEW)

        execute() shouldBeEqualTo expectedBody(A_PREVIEW)
    }

    @Test
    fun `given an audio message reply, the preview is the localized audio stub`() {
        every { fakeRepliedEvent.isAudioMessage() } returns true
        fakeStringProvider.given(CommonStrings.message_reply_to_sender_sent_audio_file, A_PREVIEW)

        execute() shouldBeEqualTo expectedBody(A_PREVIEW)
    }

    @Test
    fun `given an image message reply, the preview is the localized image stub`() {
        every { fakeRepliedEvent.isImageMessage() } returns true
        fakeStringProvider.given(CommonStrings.message_reply_to_sender_sent_image, A_PREVIEW)

        execute() shouldBeEqualTo expectedBody(A_PREVIEW)
    }

    @Test
    fun `given a video message reply, the preview is the localized video stub`() {
        every { fakeRepliedEvent.isVideoMessage() } returns true
        fakeStringProvider.given(CommonStrings.message_reply_to_sender_sent_video, A_PREVIEW)

        execute() shouldBeEqualTo expectedBody(A_PREVIEW)
    }

    @Test
    fun `given a sticker message reply, the preview is the localized sticker stub`() {
        every { fakeRepliedEvent.isSticker() } returns true
        fakeStringProvider.given(CommonStrings.message_reply_to_sender_sent_sticker, A_PREVIEW)

        execute() shouldBeEqualTo expectedBody(A_PREVIEW)
    }

    @Test
    fun `given a live location reply, the preview is the localized live location stub`() {
        every { fakeRepliedEvent.isLiveLocation() } returns true
        fakeStringProvider.given(CommonStrings.live_location_description, A_PREVIEW)

        execute() shouldBeEqualTo expectedBody(A_PREVIEW)
    }

    @Test
    fun `given a poll start reply with a question, the preview is the question`() {
        every { fakeRepliedEvent.isPollStart() } returns true
        every { fakeRepliedEvent.getPollQuestion() } returns "What is your favourite colour?"

        execute() shouldBeEqualTo expectedBody("What is your favourite colour?")
    }

    @Test
    fun `given a poll start reply without a question, the preview is the localized poll stub`() {
        every { fakeRepliedEvent.isPollStart() } returns true
        every { fakeRepliedEvent.getPollQuestion() } returns null
        fakeStringProvider.given(CommonStrings.message_reply_to_sender_created_poll, A_PREVIEW)

        execute() shouldBeEqualTo expectedBody(A_PREVIEW)
    }

    @Test
    fun `given a poll end reply with a question, the preview is the question`() {
        every { fakeRepliedEvent.isPollEnd() } returns true
        givenPollEndQuestion("Was it good?")

        execute() shouldBeEqualTo expectedBody("Was it good?")
    }

    @Test
    fun `given a poll end reply without a question, the preview is the localized poll stub`() {
        every { fakeRepliedEvent.isPollEnd() } returns true
        givenPollEndQuestion(null)
        fakeStringProvider.given(CommonStrings.message_reply_to_sender_ended_poll, A_PREVIEW)

        execute() shouldBeEqualTo expectedBody(A_PREVIEW)
    }

    @Test
    fun `given a text message reply, the preview is the message body`() {
        every { fakeTimelineEvent.getLastMessageContent() } returns
                MessageTextContent(msgType = MessageType.MSGTYPE_TEXT, body = "Hello world")

        execute() shouldBeEqualTo expectedBody("Hello world")
    }

    @Test
    fun `given no replied event found, the block shows the unresolved notice`() {
        givenTimelineEventReturns(AN_EVENT_ID, null)
        fakeStringProvider.given(CommonStrings.in_reply_to_error, "Could not load")

        execute() shouldBeEqualTo "<mx-reply><blockquote>Could not load</blockquote></mx-reply>Reply text"
    }

    private fun execute(): String {
        return processBodyOfReplyToEventUseCase.execute(
                roomId = A_ROOM_ID,
                matrixFormattedBody = A_REPLY_TO_EVENT_MATRIX_FORMATTED_BODY,
                replyToContent = fakeReplyToContent,
        )
    }

    private fun givenTimelineEventReturns(eventId: String, timelineEvent: TimelineEvent?) {
        fakeActiveSessionHolder
                .fakeSession
                .roomService()
                .getRoom(A_ROOM_ID)
                .timelineService()
                .givenTimelineEventReturns(eventId, timelineEvent)
    }

    private fun givenPollEndQuestion(question: String?) {
        val startEventId = "start-event-id"
        val relationContent = mockk<RelationDefaultContent> {
            every { eventId } returns startEventId
        }
        every { fakeRepliedEvent.getRelationContent() } returns relationContent
        val startTimelineEvent = mockk<TimelineEvent> {
            every { getLastMessageContent() } returns MessagePollContent(
                    pollCreationInfo = PollCreationInfo(question = PollQuestion(unstableQuestion = question))
            )
        }
        givenTimelineEventReturns(startEventId, startTimelineEvent)
    }
}
