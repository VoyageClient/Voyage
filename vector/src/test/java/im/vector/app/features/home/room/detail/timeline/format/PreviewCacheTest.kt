/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.format

import android.text.SpannableString
import android.text.Spanned
import im.vector.app.core.resources.ColorProvider
import im.vector.app.features.home.room.detail.timeline.helper.ForwardedMessagePresenter
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.html.PillImageSpan
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.room.model.message.MessageFormat
import org.matrix.android.sdk.api.session.room.model.message.MessageTextContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PreviewCacheTest {

    private val context = RuntimeEnvironment.getApplication()

    private val colorChanges = MutableStateFlow(0L)

    private val htmlRenderer = mockk<EventHtmlRenderer>().also {
        every { it.render(PLAIN_HTML) } answers { SpannableString("plain") }
        every { it.render(PILL_HTML) } answers {
            SpannableString("@bob").apply { setSpan(mockk<PillImageSpan>(relaxed = true), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
    }

    private val formatter = DisplayableEventFormatter(
            stringProvider = mockk(relaxed = true),
            colorProvider = ColorProvider(context),
            drawableProvider = mockk(relaxed = true),
            noticeEventFormatter = mockk(relaxed = true),
            reactionFormatter = mockk(relaxed = true),
            htmlRenderer = { htmlRenderer },
            pgpDecryptor = mockk<im.vector.app.features.pgp.PgpDecryptor>(relaxed = true).also {
                every { it.peekDecryptedBody(any()) } returns null
            },
            vectorPreferences = mockk(relaxed = true),
            matrixItemColorProvider = mockk<MatrixItemColorProvider>(relaxed = true).also {
                every { it.changes } returns colorChanges
            },
            messageTranslationStore = mockk<im.vector.app.features.translation.MessageTranslationStore>(relaxed = true).also {
                every { it.get(any<TimelineEvent>()) } returns null
            },
            pillsPostProcessorFactory = mockk(relaxed = true),
            textRendererFactory = mockk(relaxed = true),
            forwardedMessages = ForwardedMessagePresenter(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)),
            codeBlockHighlighter = mockk(relaxed = true),
    )

    private fun preview(formattedBody: String): CharSequence {
        val content = MessageTextContent(
                msgType = MessageType.MSGTYPE_TEXT,
                body = "body",
                format = MessageFormat.FORMAT_MATRIX_HTML,
                formattedBody = formattedBody,
        )
        val event = Event(type = EventType.MESSAGE, eventId = "\$event", content = content.toContent())
        val timelineEvent = TimelineEvent(root = event, localId = 1L, eventId = "\$event", senderInfo = SenderInfo("@alice:example.org", "Alice", true, null))
        return formatter.format(timelineEvent, isDm = false, appendAuthor = false)
    }

    @Test
    fun `a name color change keeps previews without pills cached`() {
        preview(PLAIN_HTML)
        colorChanges.value = 1L
        preview(PLAIN_HTML)

        verify(exactly = 1) { htmlRenderer.render(PLAIN_HTML) }
    }

    @Test
    fun `a name color change re-renders previews with pills`() {
        preview(PILL_HTML)
        colorChanges.value = 1L
        preview(PILL_HTML)

        verify(exactly = 2) { htmlRenderer.render(PILL_HTML) }
    }

    @Test
    fun `invalidating pill previews only drops those with pills`() {
        assertFalse(formatter.invalidatePillPreviews())
        preview(PLAIN_HTML)
        assertFalse(formatter.invalidatePillPreviews())
        preview(PILL_HTML)

        assertTrue(formatter.invalidatePillPreviews())
        assertFalse(formatter.invalidatePillPreviews())
        preview(PLAIN_HTML)
        preview(PILL_HTML)

        verify(exactly = 1) { htmlRenderer.render(PLAIN_HTML) }
        verify(exactly = 2) { htmlRenderer.render(PILL_HTML) }
    }

    private companion object {
        const val PLAIN_HTML = "<b>plain</b>"
        const val PILL_HTML = "<a href=\"https://matrix.to/#/@bob:example.org\">@bob</a>"
    }
}
