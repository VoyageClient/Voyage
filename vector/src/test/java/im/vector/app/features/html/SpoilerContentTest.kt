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
import im.vector.app.features.home.room.detail.timeline.tools.linkify
import im.vector.app.features.settings.VectorPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.Session
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A spoiler has to hide whatever it wraps, not just bare text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SpoilerContentTest {

    private val context = RuntimeEnvironment.getApplication().apply {
        setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light)
    }

    private val session = mockk<Session>(relaxed = true).also {
        every { it.permalinkService().isPermalinkSupported(any(), any()) } returns true
    }

    private val sessionHolder = mockk<ActiveSessionHolder>(relaxed = true).also {
        every { it.getActiveSession() } returns session
        every { it.getSafeActiveSession() } returns session
    }

    private val vectorPreferences = mockk<VectorPreferences>(relaxed = true)

    private val renderer = EventHtmlRenderer(
            MatrixHtmlPluginConfigure(ColorProvider(context), context.resources, sessionHolder),
            context,
            sessionHolder,
            vectorPreferences,
    )

    private fun spoilerRangeOf(html: String): Pair<Int, Int>? {
        val rendered = renderer.render(html) as Spanned
        val span = rendered.getSpans(0, rendered.length, SpoilerSpan::class.java).firstOrNull() ?: return null
        return rendered.getSpanStart(span) to rendered.getSpanEnd(span)
    }

    @Test
    fun `plain text in a spoiler is covered`() {
        val (start, end) = spoilerRangeOf("before <span data-mx-spoiler>hidden</span> after")!!
        assertTrue("expected a non-empty spoiler range, got $start..$end", end > start)
    }

    @Test
    fun `inline code in a spoiler is covered`() {
        val range = spoilerRangeOf("matching <span data-mx-spoiler><code>@akira0880:matrix.org</code></span> for <code>spam</code>")
        assertTrue("no spoiler span at all", range != null)
        val (start, end) = range!!
        assertTrue("expected the code to be covered, got an empty range at $start", end > start)
    }

    @Test
    fun `a spoiler over inline code survives the timeline's linkify pass`() {
        // Stripping links inside code used to take the spoiler with them, leaving the text in plain sight.
        val html = "matching <span data-mx-spoiler><code>@akira0880:matrix.org</code></span> for <code>spam</code>"
        val rendered = renderer.render(VectorHtmlCompressor().compress(html)).linkify(null) as Spanned

        val spoiler = rendered.getSpans(0, rendered.length, SpoilerSpan::class.java).firstOrNull()
        assertTrue("the spoiler was stripped alongside the links inside the code", spoiler != null)
        assertTrue("the spoiler no longer covers the code", rendered.getSpanEnd(spoiler) > rendered.getSpanStart(spoiler))
    }

    @Test
    fun `a spoiler over an emote survives the timeline's linkify pass`() {
        val html = """<span data-mx-spoiler><img src="mxc://example.org/pack" alt=":shrug:" /></span>"""
        val rendered = renderer.render(VectorHtmlCompressor().compress(html)).linkify(null) as Spanned

        assertTrue(
                "the spoiler was stripped alongside the emote's links",
                rendered.getSpans(0, rendered.length, SpoilerSpan::class.java).isNotEmpty()
        )
    }

    @Test
    fun `a link in a spoiler is covered`() {
        val range = spoilerRangeOf("""<span data-mx-spoiler><a href="https://example.org">link</a></span>""")
        assertTrue("no spoiler span at all", range != null)
        assertTrue("expected the link to be covered", range!!.second > range.first)
    }
}
