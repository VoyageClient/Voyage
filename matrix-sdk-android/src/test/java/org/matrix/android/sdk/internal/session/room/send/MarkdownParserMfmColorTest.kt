/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import io.mockk.every
import io.mockk.mockk
import org.commonmark.ext.explicitlinks.ExplicitLinksExtension
import org.commonmark.ext.maths.MathsExtension
import org.commonmark.ext.subsupstrike.SubSupStrikeExtension
import org.commonmark.ext.underline.UnderlineExtension
import org.commonmark.node.BlockQuote
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.android.sdk.internal.session.room.send.pills.TextPillsUtils

class MarkdownParserMfmColorTest {
    private val extensions = listOf(
            MfmColorExtension(),
            MathsExtension.create(),
            SubSupStrikeExtension.create(),
            ExplicitLinksExtension.create(),
            UnderlineExtension.create(),
    )
    private val parser = MarkdownParser(
            Parser.builder().extensions(extensions).build(),
            Parser.builder().extensions(listOf(SubSupStrikeExtension.create(), MfmColorExtension()))
                    .enabledBlockTypes(setOf(BlockQuote::class.java)).build(),
            HtmlRenderer.builder().extensions(extensions).softbreak("<br />").build(),
            mockk<TextPillsUtils> { every { processSpecialSpansToMarkdown(any()) } returns null },
    )

    @Test
    fun `colored bold text has a visible plain body`() {
        val result = parser.parse("$[fg.color=3a7bc2 **Example**]")
        assertEquals("Example", result.text)
        assertEquals("""<span data-mx-color="#3a7bc2"><strong>Example</strong></span>""", result.formattedText)
    }

    @Test
    fun `foreground and background accept short hex optional hash and mixed case`() {
        val result = parser.parse("before $[bg.color=#0F0 fg.color=FfF label] after")
        assertEquals("before label after", result.text)
        assertEquals("""before <span data-mx-bg-color="#00ff00" data-mx-color="#ffffff">label</span> after""", result.formattedText)
    }

    @Test
    fun `adjacent colors do not become maths`() {
        val result = parser.parse("$[fg.color=f00 red] $[fg.color=00f blue]")
        assertEquals("red blue", result.text)
        assertEquals("""<span data-mx-color="#ff0000">red</span> <span data-mx-color="#0000ff">blue</span>""", result.formattedText)
    }

    @Test
    fun `nested colors preserve links spoilers and line breaks`() {
        val result = parser.parse("$[fg.color=f00 [site](https://example.org) $[bg.color=000 ||secret||]\nnext]")
        assertEquals("site secret\nnext", result.text)
        assertTrue(result.formattedText!!.contains("""<span data-mx-bg-color="#000000"><span data-mx-spoiler>secret</span></span>"""))
        assertTrue(result.formattedText!!.contains("""href="https://example.org"""))
    }

    @Test
    fun `colors work inside markdown formatting and link labels`() {
        assertEquals("""<strong><span data-mx-color="#ff0000">red</span></strong>""", parser.parse("**$[fg.color=f00 red]**").formattedText)
        assertTrue(parser.parse("[$[fg.color=f00 red]](https://example.org)").formattedText!!.contains("""<span data-mx-color="#ff0000">red</span></a>"""))
    }

    @Test
    fun `underscore formatting retains its word boundaries inside colors`() {
        assertEquals(
                """<span data-mx-color="#ff0000"><em>italic</em> <u>underlined</u></span>""",
                parser.parse("$[fg.color=f00 _italic_ __underlined__]").formattedText
        )
        assertEquals("italic underlined", parser.parse("$[fg.color=f00 _italic_ __underlined__]").text)
    }

    @Test
    fun `code escapes and invalid colors remain literal`() {
        listOf(
                "`$[fg.color=f00 code]`",
                "``$[fg.color=f00 code]``",
                "```\n$[fg.color=f00 code]\n```",
                "    $[fg.color=f00 code]",
                "\\$[fg.color=f00 escaped]",
                "$[fg.color=red named]",
                "$[fg.color=ffff alpha]",
                "$[fg.color=ffffffff alpha]",
                "$[fg.color=f00 missing",
                "$[fg.color=f00 ]",
        ).forEach { source ->
            val result = parser.parse(source)
            assertFalse(source, result.formattedText.orEmpty().contains("data-mx-color"))
            assertEquals(source, result.text)
        }
    }

    @Test
    fun `link destinations titles and html attributes do not interpret colors`() {
        listOf(
                "[site](https://example.org \"$[fg.color=f00 title]\")",
                "<span title=\"$[fg.color=f00 title]\">text</span>",
                "![image $[fg.color=f00 alt]](mxc://example.org/picture)",
        ).forEach { source ->
            val result = parser.parse(source)
            assertFalse(source, result.formattedText.orEmpty().contains("data-mx-color"))
            assertFalse(source, result.takeFormatted().contains("VOYAGEMFM"))
        }
    }

    @Test
    fun `brackets escapes code and html entities inside colors survive`() {
        val result = parser.parse("$[fg.color=f00 [label] \\] `]` &amp; &lt;text&gt;]")
        assertEquals("[label] ] ] & <text>", result.text)
        assertTrue(result.formattedText!!.contains("<code>]</code>"))
    }

    @Test
    fun `unmatched backtick runs do not hide later color blocks`() {
        assertEquals(
                """before ``` literal <span data-mx-color="#ff0000">text</span> `` trailing""",
                parser.parse("before ``` literal $[fg.color=f00 text] `` trailing").formattedText
        )
    }

    @Test
    fun `long colored messages retain their text`() {
        val text = "long message ".repeat(500).trimEnd()
        assertEquals(text, parser.parse("$[fg.color=f00 $text]").text)
    }

    @Test
    fun `simple parser and forced parsing produce the same fallback`() {
        val source = "$[bg.color=123456 **hello**]"
        assertEquals("hello", parser.parse(source, force = true, advanced = false).text)
        assertTrue(parser.parse(source, force = true, advanced = false).formattedText!!.contains("data-mx-bg-color"))
    }
}
