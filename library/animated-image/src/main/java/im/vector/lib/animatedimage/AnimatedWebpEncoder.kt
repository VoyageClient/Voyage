/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.animatedimage

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference

data class AnimatedFrame(val bitmap: Bitmap, val durationMs: Int)

/**
 * Builds an animated WebP file from a sequence of frames + per-frame durations. Each frame is
 * round-tripped through [Bitmap.compress] (which emits a still WebP), then the inner VP8/VP8L
 * + optional ALPH sub-chunks are extracted and repackaged inside an `ANMF` chunk in the final
 * animated container. No native deps — pure RIFF mux on top of Android's built-in WebP encoder.
 *
 * Spec reference: https://developers.google.com/speed/webp/docs/riff_container
 */
object AnimatedWebpEncoder {

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
            parallel: Boolean = false,
    ): Boolean {
        if (frames.isEmpty()) return false
        val canvasW = frames[0].bitmap.width
        val canvasH = frames[0].bitmap.height

        val workers = if (parallel) minOf(4, Runtime.getRuntime().availableProcessors(), frames.size) else 1
        val perFrame = if (workers <= 1) {
            frames.map { encodeFrame(it, quality) ?: return false }
        } else {
            encodeParallel(frames, quality, workers) ?: return false
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

    class StreamWriter(file: File, private val width: Int, private val height: Int, private val quality: Int) : Closeable {
        private val output = FileOutputStream(file)
        private var frameCount = 0

        init {
            output.write(ASCII_RIFF)
            writeUInt32LE(output, 0)
            output.write(ASCII_WEBP)
            writeVP8XChunk(output, width, height, animated = true, hasAlpha = true)
            writeAnimChunk(output, 0, 0)
        }

        fun append(frames: List<AnimatedFrame>): Boolean {
            if (frames.isEmpty()) return true
            require(frames.all { it.bitmap.width == width && it.bitmap.height == height })
            val workers = if (width.toLong() * height < 65536) 1 else minOf(4, Runtime.getRuntime().availableProcessors(), frames.size)
            val encoded = if (workers <= 1) frames.map { encodeFrame(it, quality) ?: return false }
                    else encodeParallel(frames, quality, workers) ?: return false
            encoded.forEach { writeAnmfChunk(output, it) }
            frameCount += encoded.size
            return true
        }

        fun finish(): Boolean {
            if (frameCount == 0) return false
            val size = output.channel.position()
            if (size - 8 > 0xFFFFFFFFL) return false
            output.channel.position(4)
            writeUInt32LE(output, size - 8)
            output.channel.position(size)
            output.flush()
            return true
        }

        override fun close() = output.close()
    }

    private fun encodeFrame(frame: AnimatedFrame, quality: Int): FrameEncoded? {
        val buffer = ByteArrayOutputStream()
        if (!frame.bitmap.compress(webpLossyFormat(), quality, buffer)) return null
        val payload = extractInnerImagePayload(buffer.toByteArray()) ?: return null
        return FrameEncoded(frame.bitmap.width, frame.bitmap.height, frame.durationMs, payload, frame.bitmap.hasAlpha())
    }

    private fun encodeParallel(frames: List<AnimatedFrame>, quality: Int, workers: Int): List<FrameEncoded>? {
        val encoded = arrayOfNulls<FrameEncoded>(frames.size)
        val failure = AtomicReference<Throwable>()
        val threads = List(workers) { worker ->
            Thread({
                try {
                    for (index in worker until frames.size step workers) {
                        encoded[index] = encodeFrame(frames[index], quality)
                    }
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                }
            }, "webp-encoder-$worker")
        }
        threads.forEach { it.start() }
        var interrupted = false
        // The caller may recycle the frames on cancellation, so all encoders must finish first.
        threads.forEach { thread ->
            while (thread.isAlive) {
                try {
                    thread.join()
                } catch (error: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        failure.get()?.let { throw it }
        return encoded.map { it ?: return null }
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
        // VP8X flags: 0x02 = ANIM, 0x10 = alpha.
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
        // Reserved (6 bits) + blending (1 bit) + disposal (1 bit).
        // Blending must be 1 (= do not blend): every frame we write is a full-canvas replacement, so
        // alpha-blending would let the previous frame show through wherever this one is transparent.
        // Disposal stays 0 for the same reason — there is nothing to clear.
        out.write(BLEND_DO_NOT_BLEND)
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

    /** ANMF flags: blending bit set, i.e. replace the canvas rather than compositing over it. */
    private const val BLEND_DO_NOT_BLEND = 0x02
}
