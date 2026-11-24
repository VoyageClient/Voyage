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
import org.junit.Test
import org.matrix.android.sdk.internal.session.room.send.pills.TextPillsUtils

class MarkdownParserMscLinkTest {

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
    fun `MSC references link to GitHub by default`() {
        assertEquals(
                "see <a href=\"https://github.com/matrix-org/matrix-spec-proposals/pull/1234\">MSC1234</a>",
                parser.parse("see MSC1234").formattedText
        )
    }

    @Test
    fun `MSC references link to the configured site`() {
        StaticScSdkHelper.scSdkPreferenceProvider = object : StaticScSdkHelper.ScSdkPreferenceProvider {
            override fun includeSpaceMembersAsSpaceRooms() = true
            override fun mscLinkBaseUrl() = "https://msc.example.org/"
        }
        assertEquals("see <a href=\"https://msc.example.org/1234\">MSC1234</a>", parser.parse("see MSC1234").formattedText)
        assertEquals(
                "<strong>bold</strong> <a href=\"https://msc.example.org/42\">msc42</a>",
                parser.parse("**bold** msc42").formattedText
        )
    }
}
