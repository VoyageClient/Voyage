/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.reactions

import android.text.Spanned
import im.vector.app.features.html.EmoteImageSpan
import im.vector.app.features.imagepack.EmojiShortNames
import im.vector.app.features.imagepack.EmoteShortcodeProcessor
import im.vector.app.features.imagepack.ImagePackProvider
import im.vector.app.features.imagepack.ResolvedImage
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReactionTextTest {
    private val mxc = "mxc://example.org/blobcat"
    private val packs = mockk<ImagePackProvider>()
    private val reactionText = ReactionText(EmoteShortcodeProcessor(packs, EmojiShortNames(RuntimeEnvironment.getApplication().resources)))

    init {
        every { packs.cachedEmoticons(any()) } returns listOf(
                ResolvedImage("blobcat", mxc, null, null, emptySet(), null)
        )
    }

    @Test
    fun `freeform resolves custom and unicode shortcodes and preserves unknown names`() {
        assertEquals("hello $mxc 😀 :unknown:", reactionText.resolveShortcodes("!room:example.org", "hello :blobcat: :grinning: :unknown:"))
    }

    @Test
    fun `adjacent custom shortcodes and trailing text cannot merge into a media ID`() {
        assertEquals("$mxc $mxc text", reactionText.resolveShortcodes(null, ":blobcat::blobcat:text"))
    }

    @Test
    fun `literal URLs are preserved while other shortcodes resolve`() {
        val url = "mxc://example.org:8448/blobcat"
        assertEquals("$url 😀", reactionText.resolveShortcodes(null, "$url :grinning:"))
    }

    @Test
    fun `custom shortcodes adjacent to literal URLs stay separate`() {
        assertEquals("$mxc $mxc", reactionText.resolveShortcodes(null, ":blobcat:$mxc"))
    }

    @Test
    fun `a URL inside inline code does not change shortcode handling`() {
        val text = "`$mxc :blobcat:`"
        assertEquals(text, reactionText.resolveShortcodes(null, text))
    }

    @Test
    fun `multiple embedded URLs render inline with surrounding punctuation`() {
        val rendered = ReactionText.renderImages("hello ($mxc), $mxc!", false) { "https://example.org/image" } as Spanned
        assertEquals("hello (\uFFFC), \uFFFC!", rendered.toString())
        val spans = rendered.getSpans(0, rendered.length, EmoteImageSpan::class.java)
        assertEquals(listOf(mxc, mxc), spans.map { it.mxcUrl })
        assertEquals(listOf(7, 11), spans.map { rendered.getSpanStart(it) })
    }

    @Test
    fun `blocked images preserve surrounding text and never resolve media`() {
        assertEquals("hello ❓!", ReactionText.renderImages("hello $mxc!", true) { error("Blocked media resolved") }.toString())
    }

    @Test
    fun `unresolvable images retain a placeholder`() {
        assertEquals("hello ❓", ReactionText.renderImages("hello $mxc", false) { null }.toString())
    }

    @Test
    fun `only a complete image key uses the standalone image view`() {
        assertEquals(mxc, ReactionText.singleImageUrl(mxc))
        assertNull(ReactionText.singleImageUrl("hello $mxc"))
        assertNull(ReactionText.singleImageUrl("$mxc hello"))
    }
}
