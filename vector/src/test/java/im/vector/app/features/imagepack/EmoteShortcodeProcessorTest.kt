/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack

import android.text.Spanned
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.room.send.MatrixEmoteSpan
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val A_ROOM_ID = "!room:example.org"
private const val BLOBCAT_MXC = "mxc://example.org/blobcat"
private const val PARTY_MXC = "mxc://example.org/party"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EmoteShortcodeProcessorTest {

    private val imagePackProvider = mockk<ImagePackProvider>()
    private val processor = EmoteShortcodeProcessor(imagePackProvider)

    private fun anImage(shortcode: String, mxcUrl: String) = ResolvedImage(
            shortcode = shortcode,
            mxcUrl = mxcUrl,
            body = null,
            info = null,
            usages = emptySet(),
            packDisplayName = null,
    )

    private fun givenEmotes(roomId: String?, vararg images: ResolvedImage) {
        every { imagePackProvider.cachedEmoticons(roomId) } returns emptyList()
        every { imagePackProvider.getEmoticons(roomId) } returns images.toList()
    }

    private fun CharSequence.emotes(): List<Triple<String, Int, Int>> {
        val spanned = this as? Spanned ?: return emptyList()
        return spanned.getSpans(0, length, MatrixEmoteSpan::class.java)
                .map { Triple(it.mxcUrl, spanned.getSpanStart(it), spanned.getSpanEnd(it)) }
    }

    @Test
    fun `a shortcode typed outside any room resolves against the packs that apply everywhere`() {
        givenEmotes(null, anImage("blobcat", BLOBCAT_MXC))

        val result = processor.process(roomId = null, text = "hello :blobcat:")

        result.emotes() shouldBeEqualTo listOf(Triple(BLOBCAT_MXC, 6, 15))
        // The text is left as typed, so it stays the fallback for anyone who cannot show the image.
        result.toString() shouldBeEqualTo "hello :blobcat:"
    }

    @Test
    fun `several shortcodes each resolve`() {
        givenEmotes(null, anImage("blobcat", BLOBCAT_MXC), anImage("party", PARTY_MXC))

        val result = processor.process(roomId = null, text = ":blobcat: and :party:")

        result.emotes().map { it.first } shouldBeEqualTo listOf(BLOBCAT_MXC, PARTY_MXC)
    }

    @Test
    fun `a shortcode nobody has is left as text`() {
        givenEmotes(null, anImage("blobcat", BLOBCAT_MXC))

        processor.process(roomId = null, text = "hello :unknown:").emotes() shouldBeEqualTo emptyList()
    }

    @Test
    fun `a shortcode inside inline code is left as text`() {
        givenEmotes(null, anImage("blobcat", BLOBCAT_MXC))

        processor.process(roomId = null, text = "type `:blobcat:` to use it").emotes() shouldBeEqualTo emptyList()
    }

    @Test
    fun `having no emotes at all changes nothing`() {
        givenEmotes(null)

        processor.process(roomId = null, text = "hello :blobcat:").emotes() shouldBeEqualTo emptyList()
    }

    @Test
    fun `a room still resolves against its own packs`() {
        givenEmotes(A_ROOM_ID, anImage("blobcat", BLOBCAT_MXC))

        processor.process(roomId = A_ROOM_ID, text = ":blobcat:").emotes().isNotEmpty().shouldBeTrue()
    }

    @Test
    fun `a disambiguated shortcode resolves to its pack's emote`() {
        givenEmotes(null, anImage("blobcat/mine", BLOBCAT_MXC), anImage("blobcat/theirs", PARTY_MXC))

        processor.process(roomId = null, text = ":blobcat/theirs:").emotes().map { it.first } shouldBeEqualTo listOf(PARTY_MXC)
    }
}
