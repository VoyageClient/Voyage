/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterThan
import org.amshove.kluent.shouldBeLessOrEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ViewerDownsampleTest {

    @Test
    fun `an image within the budget keeps its native resolution`() {
        ViewerDownsample.getScaleFactor(1920, 1080, 1920, 1080) shouldBeEqualTo 1f
    }

    @Test
    fun `a 50 megapixel photo is scaled under the canvas draw limit`() {
        val scale = ViewerDownsample.getScaleFactor(8192, 6144, 8192, 6144)
        val bytes = (8192 * scale).toLong() * (6144 * scale).toLong() * 4

        bytes shouldBeLessOrEqualTo 64L * 1024 * 1024
    }

    @Test
    fun `an explicit smaller box still fits center`() {
        ViewerDownsample.getScaleFactor(2000, 1000, 1000, 1000) shouldBeEqualTo 0.5f
    }

    @Test
    fun `it never upscales`() {
        ViewerDownsample.getScaleFactor(100, 100, 1000, 1000) shouldBeEqualTo 1f
    }

    @Test
    fun `an exact-size decoder gets a box within the budget`() {
        val (width, height) = ViewerDownsample.bounded(8192, 6144)

        width.toLong() * height * 4 shouldBeLessOrEqualTo 64L * 1024 * 1024
        ViewerDownsample.bounded(1920, 1080) shouldBeEqualTo (1920 to 1080)
    }

    @Test
    fun `a subsampling decoder gets the smallest power of two within the budget`() {
        val sample = ViewerDownsample.sampleSizeFor(8192, 6144)

        (8192 / sample).toLong() * (6144 / sample) * 4 shouldBeLessOrEqualTo 64L * 1024 * 1024
        (8192 / (sample / 2)).toLong() * (6144 / (sample / 2)) * 4 shouldBeGreaterThan 64L * 1024 * 1024
        ViewerDownsample.sampleSizeFor(1920, 1080) shouldBeEqualTo 1
    }

    @Test
    fun `the GIF logical screen size is read from the header`() {
        val gif = byteArrayOf('G'.b, 'I'.b, 'F'.b, '8'.b, '9'.b, 'a'.b, 0x40, 0x1F, 0x00, 0x18)

        ViewerBoundedDecoders.gifScreenSize(ByteBuffer.wrap(gif)) shouldBeEqualTo (8000 to 6144)
    }

    @Test
    fun `the WebP canvas size is read from the VP8X chunk`() {
        val webp = ByteArray(30)
        "RIFF".toByteArray().copyInto(webp, 0)
        "WEBPVP8X".toByteArray().copyInto(webp, 8)
        byteArrayOf(0xFF.toByte(), 0x1F, 0x00, 0xFF.toByte(), 0x17, 0x00).copyInto(webp, 24)

        ViewerBoundedDecoders.webpCanvasSize(ByteBuffer.wrap(webp)) shouldBeEqualTo (8192 to 6144)
    }

    @Test
    fun `a still WebP has no canvas size`() {
        val webp = ByteArray(30)
        "RIFF".toByteArray().copyInto(webp, 0)
        "WEBPVP8 ".toByteArray().copyInto(webp, 8)

        ViewerBoundedDecoders.webpCanvasSize(ByteBuffer.wrap(webp)).shouldBeNull()
    }

    private val Char.b get() = code.toByte()
}
