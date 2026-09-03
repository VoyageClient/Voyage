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

    // Auto-pilling of user ids written out in the composer.

    private fun completed(text: String, caret: Int = text.length, afterPaste: Boolean = false) =
            findMentions(text, caret, requireTerminator = !afterPaste).map { text.substring(it.first, it.last + 1) }

    @Test
    fun `given an id followed by a space, when scanning, then it is complete`() {
        completed("hey @alice:example.org ") shouldBeEqualTo listOf("@alice:example.org")
    }

    @Test
    fun `given an id followed by punctuation, when scanning, then the punctuation is left out`() {
        completed("hey @alice:example.org, hi") shouldBeEqualTo listOf("@alice:example.org")
        completed("hey @alice:example.org. ") shouldBeEqualTo listOf("@alice:example.org")
    }

    @Test
    fun `given an id still being typed, when scanning, then it is not complete`() {
        completed("hey @alice:example.org") shouldBeEqualTo emptyList()
        completed("hey @alice:exa world", caret = 14) shouldBeEqualTo emptyList()
    }

    @Test
    fun `given a pasted id at the end of the text, when scanning, then it is complete`() {
        completed("hey @alice:example.org", afterPaste = true) shouldBeEqualTo listOf("@alice:example.org")
    }

    @Test
    fun `given several ids, when scanning, then all of them are found in order`() {
        completed("@alice:example.org @bob:example.org ") shouldBeEqualTo listOf("@alice:example.org", "@bob:example.org")
    }

    @Test
    fun `given an id inside a link or an email address, when scanning, then it is left alone`() {
        completed("$alice ") shouldBeEqualTo emptyList()
        completed("[Alice](https://matrix.to/#/@alice:example.org) ") shouldBeEqualTo emptyList()
        completed("mail alice@example.org now") shouldBeEqualTo emptyList()
    }

    @Test
    fun `given a room alias or a room mention, when scanning, then they are found too`() {
        completed("#room:example.org ") shouldBeEqualTo listOf("#room:example.org")
        completed("@room ") shouldBeEqualTo listOf("@room")
        completed("say @room, now") shouldBeEqualTo listOf("@room")
    }

    @Test
    fun `given a user id starting with room, when scanning, then it is not a room mention`() {
        completed("@roomba:example.org ") shouldBeEqualTo listOf("@roomba:example.org")
        completed("@roomba:example.org", afterPaste = true) shouldBeEqualTo listOf("@roomba:example.org")
    }

    @Test
    fun `given a copied room pill, when splicing ids, then the mention is the alias`() {
        spliceMentionIds("My Room hi", """<a href="https://matrix.to/#/#room:example.org">My Room</a> hi""") shouldBeEqualTo "#room:example.org hi"
    }

    @Test
    fun `given an id in brackets, when scanning, then it is complete`() {
        completed("(@alice:example.org) ") shouldBeEqualTo listOf("@alice:example.org")
    }

    @Test
    fun `given a domain being typed out, when scanning, then the dot is not read as a terminator`() {
        completed("hey @user:example.") shouldBeEqualTo emptyList()
        completed("hey @user:example.o") shouldBeEqualTo emptyList()
        completed("hey @user:example.org world", caret = 21) shouldBeEqualTo emptyList()
    }

    @Test
    fun `given a plain or html command, when scanning, then nothing is pilled`() {
        completed("/plain @alice:example.org ") shouldBeEqualTo emptyList()
        completed("/html @alice:example.org ") shouldBeEqualTo emptyList()
        completed("/me waves at @alice:example.org ") shouldBeEqualTo listOf("@alice:example.org")
    }

    @Test
    fun `given a copied message, when splicing ids, then the mention is the user id`() {
        spliceMentionIds("Alice: hello", """<a href="$alice">Alice</a>: hello""") shouldBeEqualTo "@alice:example.org: hello"
    }

    @Test
    fun `given no mention, when splicing ids, then the body is unchanged`() {
        spliceMentionIds("hello", null) shouldBeEqualTo "hello"
    }

    @Test
    fun `given a mention inside code, when scanning, then it is left alone`() {
        completed("try `@alice:example.org` here") shouldBeEqualTo emptyList()
        completed("try `@alice:example.org ") shouldBeEqualTo emptyList()
        completed("```\n@alice:example.org\n``` ") shouldBeEqualTo emptyList()
        completed("```kotlin\n@alice:example.org ") shouldBeEqualTo emptyList()
    }

    @Test
    fun `given code elsewhere in the message, when scanning, then the mention is still found`() {
        completed("`code` @alice:example.org ") shouldBeEqualTo listOf("@alice:example.org")
        completed("```\ncode\n```\n@alice:example.org ") shouldBeEqualTo listOf("@alice:example.org")
    }

    @Test
    fun `given an escaped mention, when scanning, then it is not one`() {
        completed("\\@alice:example.org ") shouldBeEqualTo emptyList()
        completed("\\#room:example.org ") shouldBeEqualTo emptyList()
        completed("\\@room ") shouldBeEqualTo emptyList()
    }

    @Test
    fun `given a send, when scanning, then an id at the end of the message is taken too`() {
        val text = "hey @alice:example.org"
        findMentions(text).map { text.substring(it.first, it.last + 1) } shouldBeEqualTo listOf("@alice:example.org")
    }
}
