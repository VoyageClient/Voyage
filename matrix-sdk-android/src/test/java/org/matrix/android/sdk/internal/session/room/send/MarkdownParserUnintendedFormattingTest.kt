/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import de.spiritcroc.matrixsdk.StaticScSdkHelper
import io.mockk.every
import io.mockk.mockk
import org.commonmark.ext.subsupstrike.SubSupStrikeExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.android.sdk.internal.session.room.send.pills.TextPillsUtils

class MarkdownParserUnintendedFormattingTest {

    private val extensions = listOf(SubSupStrikeExtension.create())
    private val parser = MarkdownParser(
            Parser.builder().extensions(extensions).build(),
            Parser.builder().extensions(extensions).build(),
            HtmlRenderer.builder().extensions(extensions).softbreak("<br />").build(),
            mockk<TextPillsUtils> { every { processSpecialSpansToMarkdown(any()) } returns null },
    )

    @After
    fun resetPreferences() {
        StaticScSdkHelper.scSdkPreferenceProvider = null
    }

    @Test
    fun `text emoticons stay literal alongside intentional formatting`() {
        listOf("^_^", "~_~", ">_<", ">.<", "^-^", "^o^", "^w^", "T_T", ";_;", "x_x", "O_O", "@_@", "*_*").forEach { emoticon ->
            val result = parser.parse("Hello $emoticon **friend**")
            assertEquals("Hello $emoticon **friend**", result.text)
            val escaped = emoticon.replace(">", "&gt;").replace("<", "&lt;")
            assertTrue("$emoticon: ${result.formattedText}", result.formattedText.orEmpty().contains(escaped))
            assertTrue(result.formattedText.orEmpty().contains("<strong>friend</strong>"))
        }
        assertEquals(null, parser.parse("^_^").formattedText)
        assertEquals("<sup>x</sup>", parser.parse("^x^").formattedText)
        assertEquals("<sub>x</sub>", parser.parse("~x~").formattedText)
    }

    @Test
    fun `quote attribution is plain text after quote with or without a blank line`() {
        listOf("> Words words\n- Aristotle", "> Words words\n\n- Aristotle").forEach { source ->
            val html = parser.parse(source).formattedText.orEmpty()
            assertTrue(html.contains("<blockquote>"))
            assertTrue(html.contains("- Aristotle"))
            assertFalse(html.contains("<li>"))
        }
    }

    @Test
    fun `lists following the attribution keep markdown list parsing`() {
        val html = parser.parse("> Words words\n\n- Aristotle\n- Plato").formattedText.orEmpty()
        assertTrue(html.contains("<li>Aristotle</li>"))
        assertTrue(html.contains("<li>Plato</li>"))
        assertTrue(parser.parse(">_<\n- item").formattedText.orEmpty().contains("<li>item</li>"))
    }

    @Test
    fun `code keeps emoticons and list markers unchanged`() {
        val source = "`^_^`\n\n```\n> quote\n- item\n~_~\n```"
        val html = parser.parse(source).formattedText.orEmpty()
        assertTrue(html.contains("<code>^_^</code>"))
        assertTrue(html.contains("&gt; quote\n- item\n~_~"))
    }

    @Test
    fun `toggle restores ordinary markdown parsing`() {
        StaticScSdkHelper.scSdkPreferenceProvider = object : StaticScSdkHelper.ScSdkPreferenceProvider {
            override fun includeSpaceMembersAsSpaceRooms() = true
            override fun preventUnintendedMarkdown() = false
        }
        assertTrue(parser.parse("> Words words\n\n- Aristotle").formattedText.orEmpty().contains("<li>Aristotle</li>"))
    }
}
