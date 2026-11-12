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

    private fun parsed(text: String) = (parseSed(text) as SedParseResult.Parsed).script

    private fun SedScript.applied(body: String): String? = (apply(body, Long.MAX_VALUE) as? SedScriptResult.Applied)?.text

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
        parsed("s/a/b/").applied("banana") shouldBeEqualTo "bbnana"
        parsed("s/a/b/g").applied("banana") shouldBeEqualTo "bbnbnb"
        parsed("s/a/b").applied("banana") shouldBeEqualTo "bbnana"
        parsed("s/na//g").applied("banana") shouldBeEqualTo "ba"
    }

    @Test
    fun `returns null when nothing matches`() {
        parsed("s/zzz/b/").applied("banana") shouldBe null
    }

    @Test
    fun `honours flags`() {
        parsed("s/HELLO/bye/i").applied("hello there") shouldBeEqualTo "bye there"
        parsed("s/hello/bye/").applied("HELLO there") shouldBe null
        parsed("s/a.b/x/s").applied("a\nb") shouldBeEqualTo "x"
        parseSed("s/a/b/z") shouldBeEqualTo SedParseResult.Invalid("unknown flag 'z'")
        parsed("s/\\w/x/a").applied("hi") shouldBeEqualTo "xi"
    }

    @Test
    fun `escaped delimiters stay part of the pattern`() {
        parsed("s/a\\/b/c/").applied("a/b!") shouldBeEqualTo "c!"
        parsed("s#a/b#c#").applied("a/b!") shouldBeEqualTo "c!"
    }

    @Test
    fun `supports backreferences and rejects unknown groups`() {
        parsed("s/(\\w+) (\\w+)/\\2 \\1/").applied("hello world") shouldBeEqualTo "world hello"
        parseSed("s/(a)/\\2/").shouldBeInstanceOf<SedParseResult.Invalid>()
    }

    @Test
    fun `dollar signs in the replacement are literal`() {
        parsed("s/price/$5/").applied("price") shouldBeEqualTo "$5"
    }

    @Test
    fun `rejects invalid regexes and empty patterns`() {
        parseSed("s/[unclosed/x/").shouldBeInstanceOf<SedParseResult.Invalid>()
        parseSed("s///").shouldBeInstanceOf<SedParseResult.Invalid>()
    }

    private fun highlighted(sed: String, body: String, prefix: String = "") =
            (parsed(sed).apply(body, Long.MAX_VALUE) as SedScriptResult.Applied).highlightedHtml(prefix)

    @Test
    fun `highlight wraps only the changed part of a replacement`() {
        highlighted("s/wrold/world/", "hello wrold") shouldBeEqualTo "hello w<u>or</u>ld"
        highlighted("s/a/a/", "a") shouldBeEqualTo "a"
        highlighted("s/b/i/", "a <b>") shouldBeEqualTo "a &lt;<u>i</u>&gt;"
        highlighted("s/cat/big cat/", "the cat") shouldBeEqualTo "the <u>big</u> cat"
    }

    @Test
    fun `a replacement sharing words with the match stays one highlight`() {
        highlighted("s/a quick fox/the quick brown fox/", "a quick fox jumps") shouldBeEqualTo
                "<u>the quick brown</u> fox jumps"
        highlighted("s/foo bar/bar foo baz/", "x foo bar") shouldBeEqualTo "x <u>bar foo baz</u>"
    }

    @Test
    fun `a deletion highlights the word it happened in`() {
        highlighted("s/devolve/evolve/", "great tung's lineage devolve to a cat") shouldBeEqualTo
                "great tung&#39;s lineage <u>evolve</u> to a cat"
        highlighted("s/worlds/world/", "hello worlds") shouldBeEqualTo "hello <u>world</u>"
        highlighted("s/two //", "one two three") shouldBeEqualTo "one <u>three</u>"
    }

    @Test
    fun `highlight marks each separate replacement`() {
        highlighted("s/teh/the/;s/recieve/receive/", "teh cat will recieve it") shouldBeEqualTo
                "t<u>he</u> cat will rec<u>ei</u>ve it"
        highlighted("s/a/o/g", "a b a") shouldBeEqualTo "<u>o</u> b <u>o</u>"
    }

    @Test
    fun `replacements separated only by whitespace merge into one highlight`() {
        highlighted("s/the/a/;s/red/blue/", "the red fox") shouldBeEqualTo "<u>a blue</u> fox"
    }

    @Test
    fun `later steps keep the highlight of earlier ones`() {
        highlighted("s/cat/dog/;s/o/i/", "cat") shouldBeEqualTo "<u>dig</u>"
        highlighted("s/teh/the/;s/the cat/the dog/", "teh cat") shouldBeEqualTo "t<u>he dog</u>"
    }

    @Test
    fun `the prefix is escaped and never highlighted`() {
        highlighted("s/a/b/", "a", prefix = "* <Bob> ") shouldBeEqualTo "* &lt;Bob&gt; <u>b</u>"
    }

    @Test
    fun `the u flag disables highlighting`() {
        parsed("s/a/b/").highlight shouldBe true
        parsed("s/a/b/u").highlight shouldBe false
        parsed("s/a/b/;s/c/d/u").highlight shouldBe false
    }

    @Test
    fun `semicolons chain expressions in order`() {
        parsed("s/teh/the/;s/recieve/receive/").applied("teh cat recieve") shouldBeEqualTo "the cat receive"
        parsed("s/a/b/g ; s#b#c#").applied("aa") shouldBeEqualTo "cb"
        parsed("s/a/b/;").steps.size shouldBeEqualTo 1
        parsed("s/a/b/;s/c/d").applied("ac") shouldBeEqualTo "bd"
    }

    @Test
    fun `lines chain expressions when every line is one`() {
        parsed("s/teh/the\ns/recieve/receive/g\n").applied("teh recieve recieve") shouldBeEqualTo "the receive receive"
        parsed("s/a/b/\n\n  s/b/c/  ").applied("a") shouldBeEqualTo "c"
        parsed("s/a/b/;s/b/c/\ns/c/d/").steps.size shouldBeEqualTo 3
    }

    @Test
    fun `a pattern with a line break is still one expression`() {
        val script = parsed("s/one\ntwo/x/")
        script.steps.size shouldBeEqualTo 1
        script.applied("one\ntwo") shouldBeEqualTo "x"
    }

    @Test
    fun `an unterminated replacement swallows the rest`() {
        parseSed("s/a/b;s/c/d") shouldBeEqualTo SedParseResult.Invalid("unknown flag 'c'")
        parsed("s/a/x; s#c#d").applied("a") shouldBeEqualTo "x; s#c#d"
    }

    @Test
    fun `a broken later step is invalid rather than ignored`() {
        parseSed("s/a/b/;hello") shouldBeEqualTo SedParseResult.Invalid("expected a complete expression after ';'")
        parseSed("s/a/b/;s/[x/y/").shouldBeInstanceOf<SedParseResult.Invalid>()
        parseSed("s/a/b/\ns/a/b/z") shouldBeEqualTo SedParseResult.Invalid("unknown flag 'z'")
        parseSed("s/a/b/ g").shouldBeInstanceOf<SedParseResult.Invalid>()
    }

    @Test
    fun `a chain fails at the first step that does not match`() {
        val script = parsed("s/a/b/;s/zzz/y/")
        val result = script.apply("a", Long.MAX_VALUE).shouldBeInstanceOf<SedScriptResult.NoMatch>()
        result.step.source shouldBeEqualTo "s/zzz/y/"
    }

    @Test
    fun `later steps see the text earlier ones produced`() {
        parsed("s/a/b/;s/b/c/").applied("a") shouldBeEqualTo "c"
        parsed("s/a/b/;s/a/c/").applied("a") shouldBe null
    }
}
