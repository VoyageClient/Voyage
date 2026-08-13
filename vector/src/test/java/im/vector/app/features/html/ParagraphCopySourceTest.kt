/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import android.text.Spanned
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.utils.toMarkdownSource
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Paragraphs are separated by drawn padding rather than a blank line, so the rendered text holds
 * only a newline (or a stranded space) between them. Copying a selection must still yield markdown
 * paragraphs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ParagraphCopySourceTest {

    private val context = RuntimeEnvironment.getApplication().apply {
        setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light)
    }
    private val fakeSessionHolder = mockk<ActiveSessionHolder>(relaxed = true)

    private val renderer = EventHtmlRenderer(
            MatrixHtmlPluginConfigure(ColorProvider(context), context.resources, fakeSessionHolder),
            context,
            fakeSessionHolder,
            mockk(relaxed = true),
    )

    private val compressor = VectorHtmlCompressor()

    private fun render(html: String) = renderer.render(compressor.compress(html)) as Spanned

    private fun Spanned.copyAll() = toMarkdownSource(0, length)

    @Test
    fun `paragraph break copies as a blank line`() {
        assertEquals("Paragraph 1\n\nParagraph 2", render("<p>Paragraph 1</p>\n<p>Paragraph 2</p>").copyAll())
    }

    @Test
    fun `paragraph break with no whitespace between the tags copies as a blank line`() {
        assertEquals("Paragraph 1\n\nParagraph 2", render("<p>Paragraph 1</p><p>Paragraph 2</p>").copyAll())
    }

    @Test
    fun `line breaks inside a paragraph stay single newlines`() {
        assertEquals("A\nB\n\nC", render("<p>A<br />B</p>\n<p>C</p>").copyAll())
    }

    @Test
    fun `a selection ending at a paragraph copies no trailing break`() {
        val rendered = render("<p>Paragraph 1</p>\n<p>Paragraph 2</p>")
        assertEquals("Paragraph 1", rendered.toMarkdownSource(0, "Paragraph 1".length))
    }

    @Test
    fun `the last paragraph adds no trailing break`() {
        assertEquals("Only", render("<p>Only</p>").copyAll())
    }

    @Test
    fun `loose list items are not turned into separate paragraphs`() {
        val rendered = render("<ul>\n<li>\n<p>first item</p>\n</li>\n<li>\n<p>second item</p>\n</li>\n</ul>\n")
        assertEquals("- first item\n- second item", rendered.copyAll())
    }
}
