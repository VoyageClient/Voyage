/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer.rainbow

import im.vector.app.test.trimIndentOneLine
import org.junit.Assert.assertEquals
import org.junit.Test

@Suppress("SpellCheckingInspection")
class RainbowGeneratorTest {

    private val rainbowGenerator = RainbowGenerator()
    private val blackToWhite = RainbowGenerator.Palette.Gradient(listOf(0, 0xFFFFFF))

    private fun rainbow(html: String) = rainbowGenerator.colorizeHtml(html, RainbowGenerator.Palette.Rainbow)

    private fun trans(html: String) = rainbowGenerator.colorizeHtml(html, RainbowGenerator.Palette.Trans)

    private fun gradient(html: String, colors: List<Int>) = rainbowGenerator.colorizeHtml(html, RainbowGenerator.Palette.Gradient(colors))

    @Test
    fun testCustomGradientInterpolation() {
        assertEquals(
                """<font color="#000000">a</font><font color="#808080">b</font><font color="#ffffff">c</font>""",
                gradient("abc", listOf(0, 0xFFFFFF)),
        )
        assertEquals(
                """<font color="#ff0000">a</font><font color="#00ff00">b</font><font color="#0000ff">c</font>""",
                gradient("abc", listOf(0xFF0000, 0x00FF00, 0x0000FF)),
        )
    }

    @Test
    fun testEntitiesAreOneLetterAndLineBreaksTakeNoStep() {
        assertEquals(
                """<font color="#000000">&lt;</font><font color="#808080">&amp;</font><font color="#ffffff">&gt;</font><br />""",
                gradient("&lt;&amp;&gt;<br />", listOf(0, 0xFFFFFF)),
        )
    }

    @Test
    fun testSelfClosingTagDoesNotSwallowTheRest() {
        assertEquals(
                """<font color="#000000">a</font><code/><font color="#ffffff">b</font>""",
                rainbowGenerator.colorizeHtml("a<code/>b", blackToWhite),
        )
    }

    @Test
    fun testHtmlKeepsMarkup() {
        assertEquals(
                """<p><strong><font color="#000000">a</font></strong><em><font color="#ffffff">b</font></em></p>""",
                rainbowGenerator.colorizeHtml("<p><strong>a</strong><em>b</em></p>", blackToWhite),
        )
    }

    @Test
    fun testHtmlLeavesCodeUncoloredAndUncounted() {
        assertEquals(
                """<font color="#000000">a</font><code>x <b>y</b></code><pre><code>z</code></pre><font color="#ffffff">b</font>""",
                rainbowGenerator.colorizeHtml("a<code>x <b>y</b></code><pre><code>z</code></pre>b", blackToWhite),
        )
    }

    @Test
    fun testHtmlMentionsAndEmoticonsTakeOneStep() {
        val mention = """<a href="https://matrix.to/#/@bob:example.org">Bob <em>B</em></a>"""
        val emoticon = """<img data-mx-emoticon src="mxc://example.org/AbCdEf" alt=":cat:" title=":cat:" height="32" />"""
        assertEquals(
                """<font color="#000000">a</font>$mention<font color="#aaaaaa">b</font>$emoticon""",
                rainbowGenerator.colorizeHtml("a${mention}b$emoticon", blackToWhite),
        )
    }

    @Test
    fun testHtmlMatrixUriMentionTakesOneStep() {
        val mention = """<a href="matrix:u/bob:example.org">Bob</a>"""
        assertEquals(
                """<font color="#000000">a</font>$mention<font color="#ffffff">b</font>""",
                rainbowGenerator.colorizeHtml("a${mention}b", blackToWhite),
        )
    }

    @Test
    fun testHtmlOrdinaryLinksAndEntities() {
        assertEquals(
                """<a href="https://example.org"><font color="#000000">a</font></a><font color="#808080">&amp;</font><font color="#ffffff">b</font>""",
                rainbowGenerator.colorizeHtml("""<a href="https://example.org">a</a>&amp;b""", blackToWhite),
        )
    }

    @Test
    fun testCustomGradientSingleColor() {
        assertEquals(
                """<font color="#abcdef">a</font> <font color="#abcdef">b</font>""",
                gradient("a b", listOf(0xABCDEF)),
        )
    }

    @Test
    fun testCustomGradientEmojiAndShortMessages() {
        val colors = listOf(0x123456, 0xFEDCBA)
        assertEquals("", gradient("", colors))
        assertEquals("""<font color="#123456">🤞</font>""", gradient("🤞", colors))
        assertEquals(
                """<font color="#123456">🤞</font><font color="#fedcba">a</font>""",
                gradient("🤞a", colors),
        )
    }

    @Test
    fun testEmpty() {
        assertEquals("", rainbow(""))
    }

    @Test
    fun testAscii1() {
        assertEquals("""<font color="#f20d0d">a</font>""", rainbow("a"))
    }

    @Test
    fun testAscii2() {
        val expected = """
            <font color="#f20d0d">a</font>
            <font color="#0df280">b</font>
        """.trimIndentOneLine()

        assertEquals(expected, rainbow("ab"))
    }

    @Test
    fun testAscii3() {
        val expected = """
            <font color="#f20d0d">T</font>
            <font color="#f24d0d">h</font>
            <font color="#f28c0d">i</font>
            <font color="#f2cc0d">s</font>
             
            <font color="#99f20d">i</font>
            <font color="#59f20d">s</font>
             
            <font color="#0df240">a</font>
             
            <font color="#0df2bf">r</font>
            <font color="#0de5f2">a</font>
            <font color="#0da6f2">i</font>
            <font color="#0d66f2">n</font>
            <font color="#0d26f2">b</font>
            <font color="#330df2">o</font>
            <font color="#730df2">w</font>
            <font color="#b30df2">!</font>
        """.trimIndentOneLine()

        assertEquals(expected, rainbow("This is a rainbow!"))
    }

    @Test
    fun testEmoji1() {
        assertEquals("""<font color="#f20d0d">🤞</font>""", rainbow("\uD83E\uDD1E")) // 🤞
    }

    @Test
    fun testEmoji2() {
        assertEquals("""<font color="#f20d0d">🤞</font>""", rainbow("🤞"))
    }

    @Test
    fun testEmoji3() {
        val expected = """
            <font color="#f20d0d">🤞</font>
            <font color="#0df280">🙂</font>
        """.trimIndentOneLine()

        assertEquals(expected, rainbow("🤞🙂"))
    }

    @Test
    fun testEmojiMix1() {
        val expected = """
            <font color="#f20d0d">H</font>
            <font color="#f25f0d">e</font>
            <font color="#f2b10d">l</font>
            <font color="#e2f20d">l</font>
            <font color="#90f20d">o</font>
             
            <font color="#0df22e">🤞</font>
             
            <font color="#0df2d1">w</font>
            <font color="#0dc1f2">o</font>
            <font color="#0d6ff2">r</font>
            <font color="#0d1df2">l</font>
            <font color="#4e0df2">d</font>
            <font color="#a00df2">!</font>
        """.trimIndentOneLine()

        assertEquals(expected, rainbow("Hello 🤞 world!"))
    }

    @Test
    fun testEmojiMix2() {
        val expected = """
            <font color="#f20d0d">a</font>
            <font color="#0df280">🤞</font>
        """.trimIndentOneLine()

        assertEquals(expected, rainbow("a🤞"))
    }

    @Test
    fun testEmojiMix3() {
        val expected = """
            <font color="#f20d0d">🤞</font>
            <font color="#0df280">a</font>
        """.trimIndentOneLine()

        assertEquals(expected, rainbow("🤞a"))
    }

    @Test
    fun testError1() {
        assertEquals("<font color=\"#f20d0d\">\uD83E</font>", rainbow("\uD83E"))
    }

    @Test
    fun testTransStops() {
        val expected = """
            <font color="#5bcefa">a</font>
            <font color="#f5a9b8">b</font>
            <font color="#ffffff">c</font>
            <font color="#f5a9b8">d</font>
            <font color="#5bcefa">e</font>
        """.trimIndentOneLine()

        assertEquals(expected, trans("abcde"))
    }

    @Test
    fun testTransSentence() {
        val expected = """
            <font color="#5bcefa">t</font>
            <font color="#8ec2e4">r</font>
            <font color="#c2b5ce">a</font>
            <font color="#f5a9b8">n</font>
            <font color="#f8c6d0">s</font>
             
            <font color="#ffffff">r</font>
            <font color="#fce2e7">i</font>
            <font color="#f8c6d0">g</font>
            <font color="#f5a9b8">h</font>
            <font color="#c2b5ce">t</font>
            <font color="#8ec2e4">s</font>
            <font color="#5bcefa">!</font>
        """.trimIndentOneLine()

        assertEquals(expected, trans("trans rights!"))
    }

    @Test
    fun testTransSingle() {
        assertEquals("""<font color="#5bcefa">a</font>""", trans("a"))
    }
}
