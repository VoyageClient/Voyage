/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer.sed

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.Test

class SedExpressionTest {

    private fun parsed(text: String) = (parseSed(text) as SedParseResult.Parsed).expression

    @Test
    fun `only a bare expression triggers`() {
        parseSed("hello") shouldBe SedParseResult.NotSed
        parseSed("sup") shouldBe SedParseResult.NotSed
        parseSed("see s/a/b/ later") shouldBe SedParseResult.NotSed
        parseSed("/plain s/a/b/") shouldBe SedParseResult.NotSed
        parseSed("s/unterminated") shouldBe SedParseResult.NotSed
        parseSed("s|a|b|") shouldBe SedParseResult.NotSed
        parseSed("s/a/b/").shouldBeInstanceOf<SedParseResult.Parsed>()
        parseSed("s#a#b#").shouldBeInstanceOf<SedParseResult.Parsed>()
        parseSed("  s/a/b/  ").shouldBeInstanceOf<SedParseResult.Parsed>()
    }

    @Test
    fun `substitutes the first match by default and all with the g flag`() {
        parsed("s/a/b/").apply("banana") shouldBeEqualTo "bbnana"
        parsed("s/a/b/g").apply("banana") shouldBeEqualTo "bbnbnb"
        parsed("s/a/b").apply("banana") shouldBeEqualTo "bbnana"
        parsed("s/na//g").apply("banana") shouldBeEqualTo "ba"
    }

    @Test
    fun `returns null when nothing matches`() {
        parsed("s/zzz/b/").apply("banana") shouldBe null
    }

    @Test
    fun `honours flags`() {
        parsed("s/HELLO/bye/i").apply("hello there") shouldBeEqualTo "bye there"
        parsed("s/hello/bye/").apply("HELLO there") shouldBe null
        parsed("s/a.b/x/s").apply("a\nb") shouldBeEqualTo "x"
        parseSed("s/a/b/z") shouldBeEqualTo SedParseResult.Invalid("unknown flag 'z'")
        parsed("s/\\w/x/a").apply("hi") shouldBeEqualTo "xi"
    }

    @Test
    fun `escaped delimiters stay part of the pattern`() {
        parsed("s/a\\/b/c/").apply("a/b!") shouldBeEqualTo "c!"
        parsed("s#a/b#c#").apply("a/b!") shouldBeEqualTo "c!"
    }

    @Test
    fun `supports backreferences and rejects unknown groups`() {
        parsed("s/(\\w+) (\\w+)/\\2 \\1/").apply("hello world") shouldBeEqualTo "world hello"
        parseSed("s/(a)/\\2/").shouldBeInstanceOf<SedParseResult.Invalid>()
    }

    @Test
    fun `dollar signs in the replacement are literal`() {
        parsed("s/price/$5/").apply("price") shouldBeEqualTo "$5"
    }

    @Test
    fun `rejects invalid regexes and empty patterns`() {
        parseSed("s/[unclosed/x/").shouldBeInstanceOf<SedParseResult.Invalid>()
        parseSed("s///").shouldBeInstanceOf<SedParseResult.Invalid>()
    }

    @Test
    fun `highlight wraps only the changed span`() {
        highlightDiff("hello wrold", "hello world") shouldBeEqualTo "hello w<u>or</u>ld"
        highlightDiff("a", "a") shouldBeEqualTo "a"
        highlightDiff("a <b>", "a <i>") shouldBeEqualTo "a &lt;<u>i</u>&gt;"
    }

    @Test
    fun `the u flag disables highlighting`() {
        parsed("s/a/b/").highlight shouldBe true
        parsed("s/a/b/u").highlight shouldBe false
    }
}
