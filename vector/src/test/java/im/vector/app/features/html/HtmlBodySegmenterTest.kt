/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlBodySegmenterTest {

    @Test
    fun `a code block inside a spoiler is still a code block`() {
        val segments = HtmlBodySegmenter.segment("<span data-mx-spoiler><pre><code>fun main()</code></pre></span>")

        assertEquals(1, segments.size)
        val code = segments.first() as BodySegment.Code
        assertEquals("fun main()", code.code)
        assertTrue(code.spoiler)
    }

    @Test
    fun `a table inside a spoiler is still a table`() {
        val segments = HtmlBodySegmenter.segment("<span data-mx-spoiler><table><tr><td>cell</td></tr></table></span>")

        val table = segments.filterIsInstance<BodySegment.Table>().single()
        assertEquals("cell", table.rows.single().cells.single().html)
        assertTrue(table.spoiler)
    }

    @Test
    fun `text around a spoilered block keeps the spoiler around it`() {
        val segments = HtmlBodySegmenter.segment("<span data-mx-spoiler>before<pre><code>x</code></pre>after</span>")

        assertEquals(3, segments.size)
        val before = segments[0] as BodySegment.Html
        val after = segments[2] as BodySegment.Html
        assertTrue(before.html.contains("data-mx-spoiler"))
        assertTrue(before.html.contains("before"))
        assertTrue(after.html.contains("data-mx-spoiler"))
        assertTrue(after.html.contains("after"))
        assertTrue((segments[1] as BodySegment.Code).spoiler)
    }

    @Test
    fun `a spoiler reason survives the re-wrapping`() {
        val segments = HtmlBodySegmenter.segment("""<span data-mx-spoiler="ending">plot<pre><code>x</code></pre></span>""")

        assertTrue((segments.first() as BodySegment.Html).html.contains("""data-mx-spoiler="ending""""))
    }

    @Test
    fun `an inline-only spoiler is left whole for the spannable`() {
        val html = "<p><span data-mx-spoiler>hidden</span> shown</p><pre><code>x</code></pre>"
        val segments = HtmlBodySegmenter.segment(html)

        assertEquals("<p><span data-mx-spoiler>hidden</span> shown</p>", (segments[0] as BodySegment.Html).html)
        assertEquals(false, (segments[1] as BodySegment.Code).spoiler)
    }

    @Test
    fun `blocks outside a spoiler are not covered`() {
        val segments = HtmlBodySegmenter.segment("<pre><code>x</code></pre>")

        assertEquals(false, (segments.single() as BodySegment.Code).spoiler)
    }
}
