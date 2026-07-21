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
import io.mockk.mockk
import me.gujun.android.span.style.VerticalPaddingSpan
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The compressor turns the newlines markdown leaves between tags into single spaces, which Markwon
 * strands on lines of their own around list boundaries; and a loose markdown list wraps each item in
 * a redundant `<p>`. Both must render like the plain tight list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ListRenderingTest {

    private val context = RuntimeEnvironment.getApplication().apply {
        setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light)
    }
    private val fakeSessionHolder = mockk<ActiveSessionHolder>(relaxed = true)

    private val renderer = EventHtmlRenderer(
            MatrixHtmlPluginConfigure(ColorProvider(context), context.resources, fakeSessionHolder),
            context,
            fakeSessionHolder,
    )

    private val compressor = VectorHtmlCompressor()

    private fun render(html: String) = renderer.render(compressor.compress(html))

    @Test
    fun `numbered list does not start with a phantom blank line`() {
        val rendered = render("<ol>\n<li>first</li>\n<li>second</li>\n<li>third</li>\n</ol>\n")
        assertEquals("first \nsecond \nthird", rendered.toString())
    }

    @Test
    fun `phantom blank lines between text and list are dropped`() {
        val rendered = render("<p>changelog:</p>\n<ol>\n<li>first</li>\n<li>second</li>\n</ol>\n")
        assertEquals("changelog:\nfirst \nsecond", rendered.toString())
    }

    @Test
    fun `loose list without brs shows no blank lines, only paragraph padding`() {
        val loose = render(
                "<ul>\n<li>\n<p>first item</p>\n</li>\n<li>\n<p>second item</p>\n</li>\n<li>\n<p>third item</p>\n</li>\n</ul>\n"
        )
        assertEquals("first item\nsecond item\nthird item", loose.toString())
        val paddingSpans = (loose as Spanned).getSpans(0, loose.length, VerticalPaddingSpan::class.java)
        assertEquals(3, paddingSpans.size)
    }

    @Test
    fun `br after a loose item's paragraph yields exactly one blank line`() {
        val rendered = render(
                "<ul>\n<li>\n<p>first</p>\n<br />\n</li>\n<li>\n<p>second</p>\n</li>\n</ul>\n"
        )
        assertEquals("first\n\nsecond", rendered.toString())
    }

    @Test
    fun `blank line only shows in the gap that has the brs`() {
        val rendered = render(
                "<ol>\n<li>Test<br />2<br />\n<br />\n</li>\n<li>3</li>\n<li>4</li>\n</ol>\n"
        )
        assertEquals("Test\n2\n\n3 \n4", rendered.toString())
    }

    @Test
    fun `tight list with double br shows one blank line`() {
        val rendered = render(
                "<ul>\n<li>first<br />\n<br />\n</li>\n<li>second</li>\n</ul>\n"
        )
        assertEquals("first\n\nsecond", rendered.toString())
    }
}
