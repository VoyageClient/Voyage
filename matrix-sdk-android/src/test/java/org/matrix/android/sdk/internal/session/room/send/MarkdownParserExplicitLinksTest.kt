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
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.matrix.android.sdk.internal.session.room.send.pills.TextPillsUtils

class MarkdownParserExplicitLinksTest {

    private var pillMarkdown: String? = null

    private val textPillsUtils = mockk<TextPillsUtils> {
        every { processSpecialSpansToMarkdown(any()) } answers { pillMarkdown }
    }

    private val extensions = listOf(ExplicitLinksExtension.create())

    private val parser = MarkdownParser(
            Parser.builder().extensions(extensions).build(),
            Parser.builder().extensions(extensions).build(),
            HtmlRenderer.builder().extensions(extensions).softbreak("<br />").build(),
            textPillsUtils,
    )

    @Test
    fun `an authored link is sent with both valueless markers`() {
        val result = parser.parse("Questions? [DM me](https://matrix.to/#/@alice:example.org)")
        assertEquals(
                """Questions? <a href="https://matrix.to/#/@alice:example.org" data-mx-link data-org.matrix.msc4550.link>DM me</a>""",
                result.formattedText
        )
        assertEquals("Questions? [DM me](https://matrix.to/#/@alice:example.org)", result.text)
    }

    @Test
    fun `an autolink and a titled link are explicit too`() {
        assertEquals(
                """<a href="https://example.org" data-mx-link data-org.matrix.msc4550.link>https://example.org</a>""",
                parser.parse("<https://example.org>").formattedText
        )
        assertEquals(
                """<a href="https://example.org" title="a &quot;site&quot;" data-mx-link data-org.matrix.msc4550.link>x</a>""",
                parser.parse("""[x](https://example.org "a \"site\"")""").formattedText
        )
    }

    @Test
    fun `a mention pill stays a plain anchor next to an identical authored link`() {
        pillMarkdown = "[Alice](https://matrix.to/#/@alice:example.org) " +
                "[Alice](https://matrix.to/#/@alice:example.org \"${ExplicitLinksExtension.MENTION_TITLE}\")"
        val result = parser.parse("ignored")
        assertEquals(
                """<a href="https://matrix.to/#/@alice:example.org" data-mx-link data-org.matrix.msc4550.link>Alice</a> """ +
                        """<a href="https://matrix.to/#/@alice:example.org">Alice</a>""",
                result.formattedText
        )
    }
}
