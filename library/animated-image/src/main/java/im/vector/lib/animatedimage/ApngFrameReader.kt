/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.animatedimage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32

/**
 * Lightweight APNG frame extractor. Parses PNG chunks, reconstructs each animation frame as a
 * standalone PNG (IHDR with frame-local dimensions + ancillaries + IDAT(s) + IEND), decodes via
 * [BitmapFactory], then composites onto an output canvas honouring fcTL `dispose_op`/`blend_op`.
 */
object ApngFrameReader {

    fun readFrames(file: File): List<AnimatedFrame>? {
        // A truncated or hand-made file reaches the chunk walk with payloads shorter than the
        // fields it reads out of them, so parsing is inside the guard along with the read.
        return try {
            val parsed = parseChunks(file.readBytes()) ?: return null
            if (parsed.frames.isEmpty()) return null
            composeFrames(parsed)
        } catch (t: Throwable) {
            Timber.w(t, "APNG: cannot read $file")
            null
        }
    }

    private data class FrameChunk(
            val width: Int,
            val height: Int,
            val x: Int,
            val y: Int,
            val delayMs: Int,
            val disposeOp: Int,
            val blendOp: Int,
            val dataChunks: MutableList<ByteArray>,
    )

    private data class ParsedApng(
            val canvasWidth: Int,
            val canvasHeight: Int,
            val ihdrPayload: ByteArray,
            val ancillaries: List<Pair<String, ByteArray>>,
            val frames: List<FrameChunk>,
    )

    private fun parseChunks(data: ByteArray): ParsedApng? {
        if (data.size < PNG_SIGNATURE.size || !data.startsWith(PNG_SIGNATURE)) return null
        var ihdrPayload: ByteArray? = null
        val ancillaries = mutableListOf<Pair<String, ByteArray>>()
        val frames = mutableListOf<FrameChunk>()
        var pendingFrame: FrameChunk? = null
        var sawFcTLBeforeIdat = false
        var canvasW = 0
        var canvasH = 0

        var i = PNG_SIGNATURE.size
        while (i + 8 <= data.size) {
            val length = readUInt32BE(data, i)
            val type = String(data, i + 4, 4, Charsets.US_ASCII)
            val dataStart = i + 8
            if (length < 0 || dataStart + length + 4 > data.size) return null
            val payload = data.copyOfRange(dataStart, dataStart + length)
            when (type) {
                "IHDR" -> {
                    ihdrPayload = payload
                    canvasW = readUInt32BE(payload, 0)
                    canvasH = readUInt32BE(payload, 4)
                }
                "acTL" -> Unit // total frame count — we just count fcTLs
                "fcTL" -> {
                    pendingFrame?.let { frames.add(it) }
                    sawFcTLBeforeIdat = true
                    pendingFrame = FrameChunk(
                            width = readUInt32BE(payload, 4),
                            height = readUInt32BE(payload, 8),
                            x = readUInt32BE(payload, 12),
                            y = readUInt32BE(payload, 16),
                            delayMs = run {
                                val num = readUInt16BE(payload, 20)
                                val den = readUInt16BE(payload, 22).let { if (it == 0) 100 else it }
                                ((num.toLong() * 1000L) / den.toLong()).toInt().coerceAtLeast(MIN_FRAME_DELAY_MS)
                            },
                            disposeOp = payload[24].toInt() and 0xFF,
                            blendOp = payload[25].toInt() and 0xFF,
                            dataChunks = mutableListOf(),
                    )
                }
                "IDAT" -> {
                    if (sawFcTLBeforeIdat) {
                        pendingFrame?.dataChunks?.add(payload)
                    }
                    // If IDAT precedes any fcTL it's the static "default image"; skipped.
                }
                "fdAT" -> {
                    // First 4 bytes are a sequence number; the rest is IDAT-equivalent payload.
                    if (payload.size > 4) pendingFrame?.dataChunks?.add(payload.copyOfRange(4, payload.size))
                }
                "IEND" -> {
                    pendingFrame?.let { frames.add(it) }
                    return ParsedApng(canvasW, canvasH, ihdrPayload ?: return null, ancillaries, frames)
                }
                "PLTE", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "bKGD", "pHYs" -> {
                    ancillaries.add(type to payload)
                }
                else -> Unit
            }
            i = dataStart + length + 4 // skip CRC
        }
        return null
    }

    private fun composeFrames(parsed: ParsedApng): List<AnimatedFrame>? {
        val canvas = Bitmap.createBitmap(parsed.canvasWidth, parsed.canvasHeight, Bitmap.Config.ARGB_8888)
        val c = Canvas(canvas)
        val srcPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        var snapshot: Bitmap? = null
        val output = ArrayList<AnimatedFrame>(parsed.frames.size)

        fun abandon(): List<AnimatedFrame>? {
            // Every frame here is a full-canvas ARGB bitmap; leaking a run of them is how a long
            // animation exhausts a small heap.
            output.forEach { it.bitmap.recycle() }
            canvas.recycle()
            snapshot?.recycle()
            return null
        }

        for (frame in parsed.frames) {
            // Snapshot the soon-to-be-overwritten region if this frame wants DISPOSE_PREVIOUS.
            if (frame.disposeOp == DISPOSE_PREVIOUS) {
                snapshot?.recycle()
                snapshot = canvas.copy(Bitmap.Config.ARGB_8888, true)
            }

            val framePng = buildPng(parsed.ihdrPayload, parsed.ancillaries, frame) ?: return abandon()
            val frameBitmap = BitmapFactory.decodeByteArray(framePng, 0, framePng.size) ?: return abandon()

            val rect = Rect(frame.x, frame.y, frame.x + frame.width, frame.y + frame.height)
            c.save()
            c.clipRect(rect)
            if (frame.blendOp == BLEND_SOURCE) {
                c.drawColor(0, PorterDuff.Mode.CLEAR)
            }
            c.drawBitmap(frameBitmap, frame.x.toFloat(), frame.y.toFloat(), null)
            c.restore()

            output.add(AnimatedFrame(canvas.copy(Bitmap.Config.ARGB_8888, false), frame.delayMs))

            when (frame.disposeOp) {
                DISPOSE_BACKGROUND -> {
                    c.save()
                    c.clipRect(rect)
                    c.drawColor(0, PorterDuff.Mode.CLEAR)
                    c.restore()
                }
                DISPOSE_PREVIOUS -> snapshot?.let { snap ->
                    c.save()
                    c.clipRect(rect)
                    c.drawColor(0, PorterDuff.Mode.CLEAR)
                    c.drawBitmap(snap, 0f, 0f, srcPaint)
                    c.restore()
                }
                else -> Unit // DISPOSE_NONE
            }
            frameBitmap.recycle()
        }
        canvas.recycle()
        snapshot?.recycle()
        return output
    }

    private fun buildPng(ihdrPayload: ByteArray, ancillaries: List<Pair<String, ByteArray>>, frame: FrameChunk): ByteArray? {
        if (ihdrPayload.size < 13) return null
        // Synthesise an IHDR with the frame's width/height (other 5 bytes — bit depth, colour
        // type, compression, filter, interlace — copied verbatim from the original IHDR).
        val frameIhdr = ByteArray(13)
        writeUInt32BE(frameIhdr, 0, frame.width)
        writeUInt32BE(frameIhdr, 4, frame.height)
        System.arraycopy(ihdrPayload, 8, frameIhdr, 8, 5)

        val out = ByteArrayOutputStream()
        out.write(PNG_SIGNATURE)
        writeChunk(out, "IHDR", frameIhdr)
        ancillaries.forEach { (type, payload) -> writeChunk(out, type, payload) }
        if (frame.dataChunks.isEmpty()) return null
        frame.dataChunks.forEach { writeChunk(out, "IDAT", it) }
        writeChunk(out, "IEND", EMPTY)
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, payload: ByteArray) {
        val length = payload.size
        out.write((length ushr 24) and 0xFF)
        out.write((length ushr 16) and 0xFF)
        out.write((length ushr 8) and 0xFF)
        out.write(length and 0xFF)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(payload)
        val crc = CRC32().apply {
            update(typeBytes)
            update(payload)
        }.value
        out.write((crc ushr 24).toInt() and 0xFF)
        out.write((crc ushr 16).toInt() and 0xFF)
        out.write((crc ushr 8).toInt() and 0xFF)
        out.write(crc.toInt() and 0xFF)
    }

    private fun writeUInt32BE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    private fun readUInt32BE(buf: ByteArray, offset: Int): Int =
            ((buf[offset].toInt() and 0xFF) shl 24) or
                    ((buf[offset + 1].toInt() and 0xFF) shl 16) or
                    ((buf[offset + 2].toInt() and 0xFF) shl 8) or
                    (buf[offset + 3].toInt() and 0xFF)

    private fun readUInt16BE(buf: ByteArray, offset: Int): Int =
            ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )
    private val EMPTY = ByteArray(0)
    private const val DISPOSE_BACKGROUND = 1
    private const val DISPOSE_PREVIOUS = 2
    private const val BLEND_SOURCE = 0
}
