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

    @Test
    fun testEmpty() {
        assertEquals("", rainbowGenerator.generate(""))
    }

    @Test
    fun testAscii1() {
        assertEquals("""<font color="#f20d0d">a</font>""", rainbowGenerator.generate("a"))
    }

    @Test
    fun testAscii2() {
        val expected = """
            <font color="#f20d0d">a</font>
            <font color="#0df280">b</font>
        """.trimIndentOneLine()

        assertEquals(expected, rainbowGenerator.generate("ab"))
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

        assertEquals(expected, rainbowGenerator.generate("This is a rainbow!"))
    }

    @Test
    fun testEmoji1() {
        assertEquals("""<font color="#f20d0d">🤞</font>""", rainbowGenerator.generate("\uD83E\uDD1E")) // 🤞
    }

    @Test
    fun testEmoji2() {
        assertEquals("""<font color="#f20d0d">🤞</font>""", rainbowGenerator.generate("🤞"))
    }

    @Test
    fun testEmoji3() {
        val expected = """
            <font color="#f20d0d">🤞</font>
            <font color="#0df280">🙂</font>
        """.trimIndentOneLine()

        assertEquals(expected, rainbowGenerator.generate("🤞🙂"))
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

        assertEquals(expected, rainbowGenerator.generate("Hello 🤞 world!"))
    }

    @Test
    fun testEmojiMix2() {
        val expected = """
            <font color="#f20d0d">a</font>
            <font color="#0df280">🤞</font>
        """.trimIndentOneLine()

        assertEquals(expected, rainbowGenerator.generate("a🤞"))
    }

    @Test
    fun testEmojiMix3() {
        val expected = """
            <font color="#f20d0d">🤞</font>
            <font color="#0df280">a</font>
        """.trimIndentOneLine()

        assertEquals(expected, rainbowGenerator.generate("🤞a"))
    }

    @Test
    fun testError1() {
        assertEquals("<font color=\"#f20d0d\">\uD83E</font>", rainbowGenerator.generate("\uD83E"))
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

        assertEquals(expected, rainbowGenerator.generateTrans("abcde"))
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

        assertEquals(expected, rainbowGenerator.generateTrans("trans rights!"))
    }

    @Test
    fun testTransSingle() {
        assertEquals("""<font color="#5bcefa">a</font>""", rainbowGenerator.generateTrans("a"))
    }
}
