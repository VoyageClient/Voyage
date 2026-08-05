/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.animatedimage

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Getting this wrong sends an animation to the still-image editor, which flattens it to one frame,
 * so both the false positives and the false negatives matter.
 */
class AnimatedImageFormatTest {

    @Test
    fun `a gif is recognised from its signature alone`() {
        AnimatedImageFormat.detect("GIF89a".toByteArray() + ByteArray(64)) shouldBeEqualTo AnimatedImageFormat.GIF
    }

    @Test
    fun `an apng is told apart from a still png by its control chunk`() {
        AnimatedImageFormat.detect(png(withAcTl = true)) shouldBeEqualTo AnimatedImageFormat.APNG
        AnimatedImageFormat.detect(png(withAcTl = false)) shouldBeEqualTo null
    }

    @Test
    fun `an animated webp is told apart from a still one by its anim chunk`() {
        AnimatedImageFormat.detect(webp(withAnim = true)) shouldBeEqualTo AnimatedImageFormat.WEBP
        AnimatedImageFormat.detect(webp(withAnim = false)) shouldBeEqualTo null
    }

    @Test
    fun `the chunk lengths are followed, not the bytes searched`() {
        // "acTL" inside the pixel data of a still image must not make it an animation.
        val payload = "acTL".toByteArray() + ByteArray(16)
        AnimatedImageFormat.detect(png(withAcTl = false, iHdrPayload = payload)) shouldBeEqualTo null
    }

    @Test
    fun `anything else is not an animated image`() {
        AnimatedImageFormat.detect(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(32)) shouldBeEqualTo null
        AnimatedImageFormat.detect(ByteArray(0)) shouldBeEqualTo null
        AnimatedImageFormat.detect(ByteArray(4)) shouldBeEqualTo null
    }

    private fun AnimatedImageFormat.Companion.detect(bytes: ByteArray) = detect(bytes.inputStream())

    private fun png(withAcTl: Boolean, iHdrPayload: ByteArray = ByteArray(13)): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.writePngChunk("IHDR", iHdrPayload)
        if (withAcTl) out.writePngChunk("acTL", ByteArray(8))
        out.writePngChunk("IDAT", ByteArray(32))
        return out.toByteArray()
    }

    private fun webp(withAnim: Boolean): ByteArray {
        val body = ByteArrayOutputStream()
        body.write("WEBP".toByteArray())
        if (withAnim) {
            body.writeRiffChunk("VP8X", ByteArray(10))
            body.writeRiffChunk("ANIM", ByteArray(6))
            body.writeRiffChunk("ANMF", ByteArray(24))
        } else {
            body.writeRiffChunk("VP8L", ByteArray(64))
        }
        val bytes = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        out.writeUInt32LE(bytes.size)
        out.write(bytes)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writePngChunk(type: String, payload: ByteArray) {
        writeUInt32BE(payload.size)
        write(type.toByteArray())
        write(payload)
        writeUInt32BE(0) // CRC, which detection never reads
    }

    private fun ByteArrayOutputStream.writeRiffChunk(type: String, payload: ByteArray) {
        write(type.toByteArray())
        writeUInt32LE(payload.size)
        write(payload)
        if (payload.size % 2 == 1) write(0)
    }

    private fun ByteArrayOutputStream.writeUInt32BE(value: Int) {
        for (shift in intArrayOf(24, 16, 8, 0)) write((value shr shift) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeUInt32LE(value: Int) {
        for (shift in intArrayOf(0, 8, 16, 24)) write((value shr shift) and 0xFF)
    }
}
