/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import android.text.Spanned
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.home.room.detail.timeline.format.DisplayableEventFormatter
import im.vector.app.features.home.room.detail.timeline.helper.ForwardedMessagePresenter
import im.vector.app.features.html.highlight.CodeBlockHighlighter
import im.vector.app.features.html.highlight.CodeTheme
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import im.vector.lib.strings.CommonStrings
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.Session
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
class CodeBlockPreviewTest {

    private val context = RuntimeEnvironment.getApplication().apply {
        setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light)
    }

    private val session = mockk<Session>(relaxed = true)

    private val sessionHolder = mockk<ActiveSessionHolder>(relaxed = true).also {
        every { it.getActiveSession() } returns session
        every { it.getSafeActiveSession() } returns session
    }

    private val renderer = EventHtmlRenderer(
            MatrixHtmlPluginConfigure(ColorProvider(context), context.resources, sessionHolder),
            context,
            sessionHolder,
            mockk(relaxed = true),
            noCodePalette(),
    )

    private val formatter = DisplayableEventFormatter(
            stringProvider = mockk<StringProvider>().also {
                every { it.getString(CommonStrings.code_block_room_list_preview) } returns "Code Block."
            },
            colorProvider = ColorProvider(context),
            drawableProvider = mockk(relaxed = true),
            noticeEventFormatter = mockk(relaxed = true),
            reactionFormatter = mockk(relaxed = true),
            htmlRenderer = { renderer },
            pgpDecryptor = mockk<im.vector.app.features.pgp.PgpDecryptor>(relaxed = true).also {
                every { it.peekDecryptedBody(any()) } returns null
            },
            vectorPreferences = mockk(relaxed = true),
            matrixItemColorProvider = mockk<im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider>(relaxed = true).also {
                every { it.changes } returns kotlinx.coroutines.flow.MutableStateFlow(0L)
            },
            messageTranslationStore = mockk<im.vector.app.features.translation.MessageTranslationStore>(relaxed = true).also {
                every { it.get(any<TimelineEvent>()) } returns null
            },
            pillsPostProcessorFactory = mockk(relaxed = true),
            textRendererFactory = mockk(relaxed = true),
            forwardedMessages = ForwardedMessagePresenter(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)),
            codeBlockHighlighter = mockk(relaxed = true),
    )

    // No room id, so the preview skips the per-room pill processors and renders the HTML as is.
    private fun previewOf(formattedBody: String): CharSequence {
        val content = MessageTextContent(
                msgType = MessageType.MSGTYPE_TEXT,
                body = "body",
                format = MessageFormat.FORMAT_MATRIX_HTML,
                formattedBody = formattedBody,
        )
        val event = Event(type = EventType.MESSAGE, eventId = "\$event", content = content.toContent())
        val timelineEvent = TimelineEvent(
                root = event,
                localId = 1L,
                eventId = "\$event",
                senderInfo = SenderInfo("@alice:example.org", "Alice", true, null),
        )
        return formatter.format(timelineEvent, isDm = false, appendAuthor = false)
    }

    @Test
    fun `a multi-line code block is named instead of run together`() {
        val preview = previewOf("<p>look:</p><pre><code class=\"language-c\">int a;\nint b;\n</code></pre>")

        assertEquals("look: Code Block.", preview.toString())
        val spanned = preview as Spanned
        assertTrue(spanned.getSpans(0, spanned.length, HtmlCodeSpan::class.java).isEmpty())
    }

    @Test
    fun `a code palette change makes a preview with inline code rebind`() {
        val paletteRenderer = EventHtmlRenderer(
                MatrixHtmlPluginConfigure(ColorProvider(context), context.resources, sessionHolder),
                context,
                sessionHolder,
                mockk(relaxed = true),
                mockk<CodeBlockHighlighter> { every { theme(any(), any()) } returns CodeTheme(0xffffffff.toInt(), 0xff0d1117.toInt(), null, emptyList()) },
        )
        val html = "run <code>make</code>"

        assertNotEquals(
                EpoxyCharSequence(renderer.render(html)),
                EpoxyCharSequence(paletteRenderer.render(html)),
        )
    }

    @Test
    fun `a single-line code block stays inline code`() {
        val preview = previewOf("<pre><code>make\n</code></pre>")

        assertEquals("make", preview.toString())
        val spans = (preview as Spanned).getSpans(0, preview.length, HtmlCodeSpan::class.java)
        assertTrue(spans.single().isBlock.not())
    }
}
