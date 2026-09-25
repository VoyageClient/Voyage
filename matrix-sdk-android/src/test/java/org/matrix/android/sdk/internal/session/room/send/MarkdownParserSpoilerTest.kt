/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import io.mockk.every
import io.mockk.mockk
import org.commonmark.ext.subsupstrike.SubSupStrikeExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.matrix.android.sdk.internal.session.room.send.pills.TextPillsUtils

class MarkdownParserSpoilerTest {

    private val textPillsUtils = mockk<TextPillsUtils> {
        every { processSpecialSpansToMarkdown(any()) } returns null
    }

    private val extensions = listOf(SubSupStrikeExtension.create())

    private val parser = MarkdownParser(
            Parser.builder().extensions(extensions).build(),
            Parser.builder().extensions(extensions).build(),
            HtmlRenderer.builder().extensions(extensions).softbreak("<br />").build(),
            textPillsUtils,
    )

    @Test
    fun `spoiler renders a bare attribute and the plain body drops the pipes`() {
        val result = parser.parse("see ||the ending|| now")
        assertEquals("see <span data-mx-spoiler>the ending</span> now", result.formattedText)
        assertEquals("see the ending now", result.text)
    }

    @Test
    fun `pipes inside code keep their delimiters in the plain body`() {
        val result = parser.parse("||a|| and `||b||`")
        assertEquals("a and `||b||`", result.text)
    }

    @Test
    fun `pipes inside a fenced block are left alone`() {
        val result = parser.parse("||a||\n\n```\n||b||\n```")
        assertEquals("a\n\n```\n||b||\n```", result.text)
    }

    @Test
    fun `plain body is untouched without a spoiler`() {
        assertEquals("**a || b**", parser.parse("**a || b**").text)
    }
}
