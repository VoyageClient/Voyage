/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream

internal data class AnimatedFrame(val bitmap: Bitmap, val durationMs: Int)

/**
 * Builds an animated WebP file from a sequence of frames + per-frame durations. Each frame is
 * round-tripped through [Bitmap.compress] (which emits a still WebP), then the inner VP8/VP8L
 * + optional ALPH sub-chunks are extracted and repackaged inside an `ANMF` chunk in the final
 * animated container. No native deps — pure RIFF mux on top of Android's built-in WebP encoder.
 *
 * Spec reference: https://developers.google.com/speed/webp/docs/riff_container
 */
internal object AnimatedWebpEncoder {

    /**
     * @param frames           the frames to write (must be non-empty, all same dimensions).
     * @param quality          WebP encoder quality 0..100.
     * @param loopCount        0 = loop forever; otherwise number of playbacks.
     * @param backgroundBgra   BGRA bytes used between frames; 0 (transparent) is fine.
     */
    fun encode(
            frames: List<AnimatedFrame>,
            quality: Int,
            out: OutputStream,
            loopCount: Int = 0,
            backgroundBgra: Int = 0,
    ): Boolean {
        if (frames.isEmpty()) return false
        val canvasW = frames[0].bitmap.width
        val canvasH = frames[0].bitmap.height

        // Encode each frame and parse the inner image data chunks (VP8 / VP8L / optional ALPH).
        val perFrame = frames.map { frame ->
            val buf = ByteArrayOutputStream()
            if (!frame.bitmap.compress(webpLossyFormat(), quality, buf)) return false
            val bytes = buf.toByteArray()
            val payload = extractInnerImagePayload(bytes) ?: return false
            FrameEncoded(frame.bitmap.width, frame.bitmap.height, frame.durationMs, payload, frame.bitmap.hasAlpha())
        }

        val anyAlpha = perFrame.any { it.hasAlpha }
        val body = ByteArrayOutputStream()
        writeVP8XChunk(body, canvasW, canvasH, animated = true, hasAlpha = anyAlpha)
        writeAnimChunk(body, backgroundBgra, loopCount)
        perFrame.forEach { writeAnmfChunk(body, it) }

        val bodyBytes = body.toByteArray()
        // RIFF wrapper
        out.write(ASCII_RIFF)
        writeUInt32LE(out, (4 + bodyBytes.size).toLong()) // size = 'WEBP' + body
        out.write(ASCII_WEBP)
        out.write(bodyBytes)
        return true
    }

    private fun webpLossyFormat(): Bitmap.CompressFormat =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }

    private data class FrameEncoded(
            val width: Int,
            val height: Int,
            val durationMs: Int,
            val imagePayload: ByteArray,
            val hasAlpha: Boolean,
    )

    private fun writeVP8XChunk(out: OutputStream, w: Int, h: Int, animated: Boolean, hasAlpha: Boolean) {
        out.write(ASCII_VP8X)
        writeUInt32LE(out, 10)
        // Flags byte: bit 1 = ICC, bit 2 = alpha, bit 3 = EXIF, bit 4 = XMP, bit 5 = ANIM.
        var flags = 0
        if (animated) flags = flags or (1 shl 1)
        if (hasAlpha) flags = flags or (1 shl 4)
        out.write(flags)
        out.write(0); out.write(0); out.write(0) // reserved
        writeUInt24LE(out, w - 1)
        writeUInt24LE(out, h - 1)
    }

    private fun writeAnimChunk(out: OutputStream, backgroundBgra: Int, loopCount: Int) {
        out.write(ASCII_ANIM)
        writeUInt32LE(out, 6)
        // Background color BGRA (little endian as 4 bytes).
        writeUInt32LE(out, (backgroundBgra.toLong() and 0xFFFFFFFFL))
        out.write(loopCount and 0xFF)
        out.write((loopCount ushr 8) and 0xFF)
    }

    private fun writeAnmfChunk(out: OutputStream, frame: FrameEncoded) {
        out.write(ASCII_ANMF)
        // Payload: 16 header bytes + image payload.
        val payloadSize = 16 + frame.imagePayload.size
        writeUInt32LE(out, payloadSize.toLong())
        writeUInt24LE(out, 0) // frame_x / 2
        writeUInt24LE(out, 0) // frame_y / 2
        writeUInt24LE(out, frame.width - 1)
        writeUInt24LE(out, frame.height - 1)
        writeUInt24LE(out, frame.durationMs.coerceIn(0, 0xFFFFFF))
        // Reserved (6 bits) + blending (1 bit, 0 = use alpha-blending) + disposal (1 bit, 0 = no disposal)
        out.write(0)
        out.write(frame.imagePayload)
        // RIFF chunks are word-aligned; emit a pad byte if payload size is odd.
        if (payloadSize and 1 == 1) out.write(0)
    }

    /**
     * Parse a still WebP (the output of [Bitmap.compress]) and return everything from the first
     * VP8/VP8L/ALPH chunk through the last image-data chunk, ready to drop straight into an
     * ANMF chunk's image-data section.
     */
    private fun extractInnerImagePayload(webpBytes: ByteArray): ByteArray? {
        if (webpBytes.size < 12) return null
        if (!webpBytes.startsWithAscii("RIFF") || !webpBytes.regionMatchesAscii(8, "WEBP")) return null
        var i = 12
        val collected = ByteArrayOutputStream()
        while (i + 8 <= webpBytes.size) {
            val type = String(webpBytes, i, 4, Charsets.US_ASCII)
            val size = readUInt32LE(webpBytes, i + 4).toInt()
            val dataStart = i + 8
            if (dataStart + size > webpBytes.size) return null
            when (type) {
                "VP8 ", "VP8L", "ALPH" -> {
                    // Re-emit this chunk verbatim (type + size + data + padding) for the ANMF payload.
                    collected.write(webpBytes, i, 8 + size + (size and 1))
                }
                else -> Unit // skip VP8X, EXIF, XMP, ICCP, etc.
            }
            i = dataStart + size + (size and 1)
        }
        return collected.takeIf { it.size() > 0 }?.toByteArray()
    }

    private fun writeUInt32LE(out: OutputStream, value: Long) {
        out.write((value and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 24) and 0xFF).toInt())
    }

    private fun writeUInt24LE(out: OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xFF) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun ByteArray.startsWithAscii(s: String): Boolean = regionMatchesAscii(0, s)

    private fun ByteArray.regionMatchesAscii(offset: Int, s: String): Boolean {
        if (offset + s.length > size) return false
        for (i in s.indices) {
            if (this[offset + i].toInt() != s[i].code) return false
        }
        return true
    }

    private val ASCII_RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val ASCII_WEBP = "WEBP".toByteArray(Charsets.US_ASCII)
    private val ASCII_VP8X = "VP8X".toByteArray(Charsets.US_ASCII)
    private val ASCII_ANIM = "ANIM".toByteArray(Charsets.US_ASCII)
    private val ASCII_ANMF = "ANMF".toByteArray(Charsets.US_ASCII)
}
