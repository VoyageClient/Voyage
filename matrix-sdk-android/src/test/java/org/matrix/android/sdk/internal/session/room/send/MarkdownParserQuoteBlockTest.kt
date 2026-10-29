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

class MarkdownParserQuoteBlockTest {

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
    fun `fenced block quotes every line until the closer`() {
        val result = parser.parse("<<<\na\nb\n>>>\nafter")
        assertEquals("<blockquote>\n<p>a<br />b</p>\n</blockquote>\n<p>after</p>", result.formattedText)
        assertEquals("> a\n> b\n\nafter", result.text)
    }

    @Test
    fun `text may follow the opener on the same line`() {
        val result = parser.parse("<<< a\nb\n>>>")
        assertEquals("<blockquote>\n<p>a<br />b</p>\n</blockquote>", result.formattedText)
        assertEquals("> a\n> b", result.text)
    }

    @Test
    fun `unclosed block runs to the end of the message`() {
        val result = parser.parse("before\n\n<<<\na\n\nb")
        assertEquals("<p>before</p>\n<blockquote>\n<p>a</p>\n<p>b</p>\n</blockquote>", result.formattedText)
        assertEquals("before\n\n> a\n>\n> b", result.text)
    }

    @Test
    fun `markdown inside the block is still rendered`() {
        val result = parser.parse("<<<\n**bold**\n>>>")
        assertEquals("<blockquote>\n<p><strong>bold</strong></p>\n</blockquote>", result.formattedText)
    }

    @Test
    fun `closer inside a code fence within the block does not end it`() {
        val result = parser.parse("<<<\n```\n>>>\n```\n>>>")
        assertEquals("<blockquote>\n<pre><code>&gt;&gt;&gt;\n</code></pre>\n</blockquote>", result.formattedText)
    }

    @Test
    fun `opener inside a code fence is left alone`() {
        val result = parser.parse("```\n<<<\n```")
        assertEquals("<pre><code>&lt;&lt;&lt;\n</code></pre>", result.formattedText)
        assertEquals("```\n<<<\n```", result.text)
    }

    @Test
    fun `triple chevron outside a block keeps its nested quote meaning`() {
        val result = parser.parse(">>> a")
        assertEquals(
                "<blockquote>\n<blockquote>\n<blockquote>\n<p>a</p>\n</blockquote>\n</blockquote>\n</blockquote>",
                result.formattedText
        )
    }
}
