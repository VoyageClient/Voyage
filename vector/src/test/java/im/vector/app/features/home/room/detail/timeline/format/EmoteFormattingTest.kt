/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.format

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.home.room.detail.timeline.tools.asEmoteBody
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.room.model.message.MessageEmoteContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * An m.emote reads as an action attributed to its sender, rendered as an italic "Sender action"
 * rather than the IRC-style "* Sender action".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EmoteFormattingTest {

    private val context = RuntimeEnvironment.getApplication().apply {
        setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light)
    }

    private val formatter = DisplayableEventFormatter(
            stringProvider = mockk<StringProvider>(relaxed = true),
            colorProvider = ColorProvider(context),
            drawableProvider = mockk(relaxed = true),
            noticeEventFormatter = mockk(relaxed = true),
            reactionFormatter = mockk(relaxed = true),
            htmlRenderer = { mockk(relaxed = true) },
            pgpDecryptor = mockk(relaxed = true),
            messageTranslationStore = mockk<im.vector.app.features.translation.MessageTranslationStore>(relaxed = true).also {
                every { it.get(any()) } returns null
            },
            pillsPostProcessorFactory = mockk(relaxed = true),
            textRendererFactory = mockk(relaxed = true),
    )

    // No room id, so the preview skips the per-room pill processors and renders the body as is.
    private fun previewOf(body: String): CharSequence {
        val content = MessageEmoteContent(msgType = MessageType.MSGTYPE_EMOTE, body = body)
        val event = Event(type = EventType.MESSAGE, eventId = "\$event", content = content.toContent())
        val timelineEvent = TimelineEvent(
                root = event,
                localId = 1L,
                eventId = "\$event",
                displayIndex = 0,
                senderInfo = SenderInfo("@alice:example.org", "Alice", true, null),
        )
        return formatter.format(timelineEvent, isDm = false, appendAuthor = false)
    }

    private fun Spanned.italicRange(): Pair<Int, Int>? {
        val span = getSpans(0, length, StyleSpan::class.java).singleOrNull { it.style == Typeface.ITALIC } ?: return null
        return getSpanStart(span) to getSpanEnd(span)
    }

    @Test
    fun `the room list preview names the sender without the asterisk`() {
        assertEquals("Alice waves", previewOf("waves").toString())
    }

    @Test
    fun `the room list preview italicises the whole emote`() {
        val preview = previewOf("waves") as Spanned
        assertEquals(0 to preview.length, preview.italicRange())
    }

    @Test
    fun `a previewed emote body names the sender without the asterisk`() {
        assertEquals("Alice waves", "waves".asEmoteBody("Alice").toString())
    }

    @Test
    fun `a previewed emote body is italicised throughout`() {
        val body = "waves".asEmoteBody("Alice") as Spanned
        assertEquals(0 to body.length, body.italicRange())
    }
}
