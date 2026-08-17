/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class MentionLinksTest {

    private val alice = "https://matrix.to/#/@alice:example.org"

    @Test
    fun `given no formatted body, when splicing, then the body is unchanged`() {
        spliceMentionLinks("Alice: hello", null) shouldBeEqualTo "Alice: hello"
    }

    @Test
    fun `given a mention labelled with the display name, when splicing, then it becomes a markdown permalink`() {
        spliceMentionLinks("Alice: hello", """<a href="$alice">Alice</a>: hello""") shouldBeEqualTo "[Alice]($alice): hello"
    }

    @Test
    fun `given a mention labelled with the user id, when splicing, then the display name locates it in the body`() {
        spliceMentionLinks(
                "Evan could you look",
                """<a href="$alice">@alice:example.org</a> could you look""",
        ) { listOf("Evan") } shouldBeEqualTo "[Evan]($alice) could you look"
    }

    @Test
    fun `given a body spelling the mention as the user id, when splicing, then it is still found`() {
        spliceMentionLinks("@alice:example.org hi", """<a href="$alice">Alice</a> hi""") shouldBeEqualTo "[@alice:example.org]($alice) hi"
    }

    @Test
    fun `given extra anchor attributes, when splicing, then the mention is still recognised`() {
        spliceMentionLinks(
                "hi Alice",
                """hi <a class="mx-pill" href="$alice" rel="noopener">Alice</a>"""
        ) shouldBeEqualTo "hi [Alice]($alice)"
    }

    @Test
    fun `given a via parameter on the permalink, when splicing, then the bare user id is used`() {
        spliceMentionLinks("Alice hi", """<a href="$alice?via=example.org">Alice</a> hi""") shouldBeEqualTo "[Alice]($alice) hi"
    }

    @Test
    fun `given repeated mentions of the same user, when splicing, then each occurrence is spliced once`() {
        val anchor = """<a href="$alice">Alice</a>"""
        spliceMentionLinks("Alice and Alice", "$anchor and $anchor") shouldBeEqualTo "[Alice]($alice) and [Alice]($alice)"
    }

    @Test
    fun `given an escaped label, when splicing, then it is matched against the unescaped body`() {
        spliceMentionLinks(
                "Bob & Co: hi",
                """<a href="https://matrix.to/#/@bob:example.org">Bob &amp; Co</a>: hi"""
        ) shouldBeEqualTo "[Bob & Co](https://matrix.to/#/@bob:example.org): hi"
    }

    @Test
    fun `given a display name inside a longer word, when splicing, then it is not matched`() {
        val body = "Evanescent music"
        spliceMentionLinks(body, """<a href="$alice">@alice:example.org</a> music""") { listOf("Evan") } shouldBeEqualTo body
    }

    @Test
    fun `given a room link, when splicing, then it is left alone`() {
        val body = "see #room:example.org"
        spliceMentionLinks(body, """see <a href="https://matrix.to/#/#room:example.org">#room:example.org</a>""") shouldBeEqualTo body
    }

    @Test
    fun `given no name that occurs in the body, when splicing, then that mention is skipped`() {
        val body = "hello"
        spliceMentionLinks(body, """<a href="$alice">Alice</a> hello""") shouldBeEqualTo body
    }

    @Test
    fun `given a label carrying link syntax, when splicing, then that mention is skipped`() {
        val body = "[Alice] hi"
        spliceMentionLinks(body, """<a href="$alice">[Alice]</a> hi""") shouldBeEqualTo body
    }

    @Test
    fun `given markup inside the anchor, when splicing, then that mention is skipped`() {
        val body = "Alice hi"
        spliceMentionLinks(body, """<a href="$alice"><b>Alice</b></a> hi""") shouldBeEqualTo body
    }
}
