/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import io.mockk.every
import io.mockk.mockk
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.matrix.android.sdk.internal.session.room.send.pills.TextPillsUtils

class MarkdownParserLooseListTest {

    private val textPillsUtils = mockk<TextPillsUtils> {
        every { processSpecialSpansToMarkdown(any()) } returns null
    }

    private val parser = MarkdownParser(
            Parser.builder().build(),
            Parser.builder().build(),
            HtmlRenderer.builder().softbreak("<br />").build(),
            textPillsUtils,
    )

    @Test
    fun `tight lists stay tight`() {
        val result = parser.parse("- first\n- second")
        assertEquals(
                "<ul>\n<li>first</li>\n<li>second</li>\n</ul>",
                result.formattedText
        )
    }

    @Test
    fun `blank lines between items become brs and the list stays p-free`() {
        assertEquals(
                "<ul>\n<li>first<br />\n<br />\n</li>\n<li>second</li>\n</ul>",
                parser.parse("- first\n\n- second").formattedText
        )
        assertEquals(
                "<ul>\n<li>first<br />\n<br />\n<br />\n</li>\n<li>second</li>\n</ul>",
                parser.parse("- first\n\n\n- second").formattedText
        )
        assertEquals(
                "<ol>\n<li>first<br />\n<br />\n<br />\n<br />\n</li>\n<li>second</li>\n</ol>",
                parser.parse("1. first\n\n\n\n2. second").formattedText
        )
    }

    @Test
    fun `only the gap with the blank line gets brs`() {
        assertEquals(
                "<ol>\n<li>Test<br />2<br />\n<br />\n</li>\n<li>3</li>\n<li>4</li>\n</ol>",
                parser.parse("1. Test\n2\n\n2. 3\n3. 4").formattedText
        )
    }

    @Test
    fun `items with several paragraphs keep the loose form with single brs`() {
        val result = parser.parse("- first\n\n  more of first\n\n- second")
        assertEquals(
                "<ul>\n<li>\n<p>first</p>\n<p>more of first</p>\n<br />\n</li>\n<li>\n<p>second</p>\n</li>\n</ul>",
                result.formattedText
        )
    }

    @Test
    fun `extra blank lines inside a code fence are untouched`() {
        val result = parser.parse("```\na\n\n\nb\n```\n- item")
        assertEquals(
                "<pre><code>a\n\n\nb\n</code></pre>\n<ul>\n<li>item</li>\n</ul>",
                result.formattedText
        )
    }
}
