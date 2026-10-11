/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html.highlight

import org.junit.Assert.assertEquals
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
