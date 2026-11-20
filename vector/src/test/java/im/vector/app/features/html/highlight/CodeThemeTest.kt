/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html.highlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CodeThemeTest {

    private val themes by lazy { CodeThemes.load(File("src/main/assets/highlightjs/themes.json").inputStream()) }

    @Test
    fun `scopes map to highlight js css classes`() {
        assertEquals(setOf("hljs-title", "class_", "inherited__"), CodeTheme.cssClasses("title.class.inherited"))
        assertEquals(setOf("hljs-keyword"), CodeTheme.cssClasses("keyword"))
        assertEquals(setOf("language-xml"), CodeTheme.cssClasses("language:xml"))
    }

    @Test
    fun `every palette has both variants`() {
        CodePalette.values().forEach { palette ->
            assertTrue(palette.name, themes.themeFor(palette, light = true) != null && themes.themeFor(palette, light = false) != null)
        }
    }

    @Test
    fun `every palette tells diff additions and deletions apart`() {
        CodePalette.values().forEach { palette ->
            listOf(true, false).forEach { light ->
                val theme = themes.themeFor(palette, light)!!
                val addition = theme.styleFor(listOf("addition"))
                val deletion = theme.styleFor(listOf("deletion"))
                val name = "$palette light=$light"
                assertFalse(name, addition.isEmpty)
                assertFalse(name, deletion.isEmpty)
                assertNotEquals(name, addition, deletion)
            }
        }
    }

    @Test
    fun `every palette keeps markdown emphasis italic and strong bold`() {
        CodePalette.values().forEach { palette ->
            listOf(true, false).forEach { light ->
                val theme = themes.themeFor(palette, light)!!
                assertTrue("$palette light=$light", theme.styleFor(listOf("emphasis")).italic)
                assertTrue("$palette light=$light", theme.styleFor(listOf("strong")).bold)
            }
        }
    }

    @Test
    fun `css colors the exporter has to translate survive`() {
        // vs2015: `.hljs-section { color: gold }`.
        assertEquals(0xffffd700.toInt(), themes.themeFor(CodePalette.VISUAL_STUDIO, light = false)!!.styleFor(listOf("section")).color)
        // nord: `background-color: rgba(163, 190, 140, 0.5)`.
        assertEquals(0x80a3be8c.toInt(), themes.themeFor(CodePalette.NORD, light = false)!!.styleFor(listOf("addition")).background)
        // base16: `.hljs-operator { opacity: 0.7 }` on the color an earlier rule gave it.
        assertEquals(0xb393a1a1.toInt(), themes.themeFor(CodePalette.SOLARIZED, light = false)!!.styleFor(listOf("operator")).color)
    }

    @Test
    fun `xterm colors diffs in terminal green and red`() {
        val dark = themes.themeFor(CodePalette.XTERM, light = false)!!
        assertEquals(0xff00ff00.toInt(), dark.styleFor(listOf("addition")).color)
        assertEquals(0xffff0000.toInt(), dark.styleFor(listOf("deletion")).color)

        val light = themes.themeFor(CodePalette.XTERM, light = true)!!
        assertEquals(0xff008700.toInt(), light.styleFor(listOf("addition")).color)
        assertEquals(0xffaf0000.toInt(), light.styleFor(listOf("deletion")).color)
    }

    @Test
    fun `visual studio light shades diff lines instead of coloring additions red`() {
        val vs = themes.themeFor(CodePalette.VISUAL_STUDIO, light = true)!!
        assertNull(vs.styleFor(listOf("addition")).color)
        assertEquals(0xffd4f0d4.toInt(), vs.styleFor(listOf("addition")).background)
    }

    @Test
    fun `element dark uses atom one dark with element's overrides on top`() {
        val theme = themes.themeFor(CodePalette.ELEMENT, light = false)!!

        assertEquals(0xffc678dd.toInt(), theme.styleFor(listOf("keyword")).color)
        assertTrue(theme.styleFor(listOf("comment")).italic)
        // atom-one-dark colors tags, Element's `.hljs-tag { color: inherit }` wins.
        assertNull(theme.styleFor(listOf("tag")).color)
        assertEquals(0xff1a4b59.toInt(), theme.styleFor(listOf("addition")).background)
    }

    @Test
    fun `themes carry their block colors except the element palette`() {
        val github = themes.themeFor(CodePalette.GITHUB, light = false)!!
        assertEquals(0xff0d1117.toInt(), github.background)
        assertEquals(0xffc9d1d9.toInt(), github.textColor)

        val element = themes.themeFor(CodePalette.ELEMENT, light = false)!!
        assertNull(element.background)
        assertNull(element.textColor)
    }

    @Test
    fun `schildichat highlights like element on its own panel`() {
        val schildi = themes.themeFor(CodePalette.SCHILDICHAT, light = false)!!
        val element = themes.themeFor(CodePalette.ELEMENT, light = false)!!

        assertEquals(element.styleFor(listOf("keyword")), schildi.styleFor(listOf("keyword")))
        assertEquals(0xff121212.toInt(), schildi.background)
        assertEquals(0xff121212.toInt(), schildi.border)
    }

    @Test
    fun `compound and descendant selectors only match where they apply`() {
        val github2015 = themes.themeFor(CodePalette.ELEMENT_2015, light = true)!!

        // `.hljs-tag .hljs-attr` vs a bare attr.
        assertEquals(0xff008080.toInt(), github2015.styleFor(listOf("tag", "attr")).color)
        assertNull(github2015.styleFor(listOf("attr")).color)
        assertTrue(github2015.styleFor(listOf("title")).bold)
    }
}
