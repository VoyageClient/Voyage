/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import android.text.Spanned
import android.widget.TextView
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.home.room.detail.timeline.format.DisplayableEventFormatter
import im.vector.app.features.settings.VectorPreferences
import io.mockk.every
import io.mockk.mockk
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan
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

/**
 * A formula reaches the timeline as one atomic ReplacementSpan, rendered before its text is set. An
 * ellipsized room list line cannot split that span, so a preview keeps the LaTeX source as text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LatexPreviewTest {

    private val latex = "e^{i\\pi}+1=0"
    private val html = "Euler: <span data-mx-maths=\"$latex\"><code>$latex</code></span>"

    private val context = RuntimeEnvironment.getApplication().apply {
        setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light)
        // The app gets this from jlatexmath's own ContentProvider, which Robolectric doesn't run.
        ru.noties.jlatexmath.JLatexMathAndroid.init(this)
    }

    private val session = mockk<Session>(relaxed = true).also {
        every { it.permalinkService().isPermalinkSupported(any(), any()) } returns true
    }

    private val sessionHolder = mockk<ActiveSessionHolder>(relaxed = true).also {
        every { it.getActiveSession() } returns session
        every { it.getSafeActiveSession() } returns session
    }

    private val vectorPreferences = mockk<VectorPreferences>(relaxed = true).also {
        every { it.latexMathsIsEnabled() } returns true
    }

    private val renderer = EventHtmlRenderer(
            MatrixHtmlPluginConfigure(ColorProvider(context), context.resources, sessionHolder),
            context,
            sessionHolder,
            vectorPreferences,
    )

    private val formatter = DisplayableEventFormatter(
            stringProvider = mockk<StringProvider>(relaxed = true),
            colorProvider = ColorProvider(context),
            drawableProvider = mockk(relaxed = true),
            noticeEventFormatter = mockk(relaxed = true),
            reactionFormatter = mockk(relaxed = true),
            htmlRenderer = { renderer },
            pgpDecryptor = mockk<im.vector.app.features.pgp.PgpDecryptor>(relaxed = true).also {
                every { it.peekDecryptedBody(any()) } returns null
            },
            matrixItemColorProvider = mockk<im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider>(relaxed = true).also {
                every { it.changes } returns kotlinx.coroutines.flow.MutableStateFlow(0L)
            },
            messageTranslationStore = mockk<im.vector.app.features.translation.MessageTranslationStore>(relaxed = true).also {
                every { it.get(any()) } returns null
            },
            pillsPostProcessorFactory = mockk(relaxed = true),
            textRendererFactory = mockk(relaxed = true),
    )

    // No room id, so the preview skips the per-room pill processors and renders the HTML as is.
    private fun previewOf(formattedBody: String): CharSequence {
        val content = MessageTextContent(
                msgType = MessageType.MSGTYPE_TEXT,
                body = latex,
                format = MessageFormat.FORMAT_MATRIX_HTML,
                formattedBody = formattedBody,
        )
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

    @Test
    fun `the timeline keeps the formula span`() {
        val rendered = renderer.render(html) as Spanned
        assertEquals(1, rendered.getSpans(0, rendered.length, JLatexAsyncDrawableSpan::class.java).size)
    }

    @Test
    fun `a formula is rendered before the text is set`() {
        val rendered = renderer.render(html) as Spanned
        val span = rendered.getSpans(0, rendered.length, JLatexAsyncDrawableSpan::class.java).single()
        LatexRenderCache.applyTo(TextView(context), rendered)
        assertTrue("the span still has no drawable to show", span.getDrawable().hasResult())
    }

    // LatexRenderCache tells the two apart by class, the inline one being a package private subclass.
    @Test
    fun `block and inline formulas carry distinct span classes`() {
        val inline = renderer.render(html) as Spanned
        val block = renderer.render("<div data-mx-maths=\"$latex\"><code>$latex</code></div>") as Spanned
        val inlineSpan = inline.getSpans(0, inline.length, JLatexAsyncDrawableSpan::class.java).single()
        val blockSpan = block.getSpans(0, block.length, JLatexAsyncDrawableSpan::class.java).single()
        assertEquals(JLatexAsyncDrawableSpan::class.java, blockSpan.javaClass)
        assertNotEquals(JLatexAsyncDrawableSpan::class.java, inlineSpan.javaClass)
    }

    @Test
    fun `the preview drops the formula span and keeps its source`() {
        val preview = previewOf(html)
        assertTrue(preview.toString().contains(latex))
        val spanned = preview as Spanned
        assertEquals(0, spanned.getSpans(0, spanned.length, JLatexAsyncDrawableSpan::class.java).size)
    }
}
