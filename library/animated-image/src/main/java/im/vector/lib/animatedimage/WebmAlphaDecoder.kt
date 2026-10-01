/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.animatedimage

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Decodes a [WebmVideo] to ARGB frames, transparency included. MediaCodec ignores WebM's alpha channel,
 * which is a second VP8/VP9 stream carried in BlockAdditional, so it runs one decoder per stream and uses
 * the alpha stream's luma plane as the frame's alpha. Output buffers are read directly (no Surface).
 */
@RequiresApi(Build.VERSION_CODES.KITKAT)
class WebmAlphaDecoder(private val video: WebmVideo) {

    /**
     * Calls [onFrame] once per frame, in order, with a reused bitmap valid only during the call, and the
     * frame's display duration.
     */
    fun decode(onFrame: (Bitmap, Long) -> Unit) {
        val color = PlaneDecoder(video, alpha = false)
        val alpha = if (video.hasAlpha) PlaneDecoder(video, alpha = true) else null
        val width = video.width
        val height = video.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val durations = video.frames.indices.associate { video.frames[it].timeUs to video.durationUs(it) }
        val pendingColor = ArrayDeque<Pair<Long, IntArray>>()
        val pendingAlpha = ArrayDeque<Pair<Long, ByteArray>>()
        try {
            color.start()
            alpha?.start()
            var colorInput = 0
            var alphaInput = 0
            var emitted = 0
            var stalls = 0
            while (emitted < video.frames.size) {
                if (colorInput < video.frames.size) {
                    val frame = video.frames[colorInput]
                    if (color.queue(frame.data, frame.timeUs) && ++colorInput == video.frames.size) color.queueEnd()
                }
                if (alpha != null && alphaInput < video.frames.size) {
                    val frame = video.frames[alphaInput]
                    // A frame without alpha data is left opaque; it never reaches the alpha decoder.
                    val queued = frame.alpha == null || alpha.queue(frame.alpha, frame.timeUs)
                    if (queued && ++alphaInput == video.frames.size) alpha.queueEnd()
                }
                var progressed = color.drain { pts, pixels -> pendingColor.addLast(pts to pixels.argb!!) }
                if (alpha != null) progressed = alpha.drain { pts, pixels -> pendingAlpha.addLast(pts to pixels.luma!!) } || progressed
                while (pendingColor.isNotEmpty()) {
                    val (pts, argb) = pendingColor.first()
                    val frameAlpha = video.frames.firstOrNull { it.timeUs == pts }?.alpha
                    val luma = if (alpha != null && frameAlpha != null) {
                        while (pendingAlpha.isNotEmpty() && pendingAlpha.first().first < pts) pendingAlpha.removeFirst()
                        val head = pendingAlpha.firstOrNull()
                        if (head == null || head.first != pts) {
                            if (alpha.ended) null else break
                        } else {
                            pendingAlpha.removeFirst().second
                        }
                    } else {
                        null
                    }
                    pendingColor.removeFirst()
                    if (luma != null) applyAlpha(argb, luma)
                    bitmap.setPixels(argb, 0, width, 0, 0, width, height)
                    onFrame(bitmap, durations[pts] ?: video.durationUs(emitted))
                    emitted++
                    progressed = true
                }
                if (color.ended && pendingColor.isEmpty()) break
                stalls = if (progressed) 0 else stalls + 1
                if (stalls > MAX_STALLS) throw IOException("VP9 decoder stalled")
            }
            if (emitted == 0) throw IOException("No frames decoded")
        } finally {
            color.release()
            alpha?.release()
            bitmap.recycle()
        }
    }

    private fun applyAlpha(argb: IntArray, luma: ByteArray) {
        val count = minOf(argb.size, luma.size)
        for (i in 0 until count) {
            argb[i] = (argb[i] and 0x00FFFFFF) or ((luma[i].toInt() and 0xFF) shl 24)
        }
    }

    private class Pixels(val argb: IntArray?, val luma: ByteArray?)

    private class PlaneDecoder(private val video: WebmVideo, private val alpha: Boolean) {
        private val codec: MediaCodec = createDecoder(video.codecMime)
        private val info = MediaCodec.BufferInfo()
        var ended = false
            private set

        fun start() {
            val format = MediaFormat.createVideoFormat(video.codecMime, video.width, video.height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            }
            codec.configure(format, null, null, 0)
            codec.start()
        }

        @Suppress("DEPRECATION")
        fun queue(data: ByteArray, timeUs: Long): Boolean {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) return false
            val buffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) codec.getInputBuffer(index)!! else codec.inputBuffers[index]
            buffer.clear()
            buffer.put(data)
            codec.queueInputBuffer(index, 0, data.size, timeUs, 0)
            return true
        }

        fun queueEnd() {
            var index = -1
            var attempts = 0
            while (index < 0 && attempts++ < MAX_STALLS) index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) throw IOException("Decoder never accepted end of stream")
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }

        /** Hands each available output to [onOutput]; returns whether anything came out. */
        fun drain(onOutput: (Long, Pixels) -> Unit): Boolean {
            var any = false
            while (!ended) {
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index >= 0 -> {
                        any = true
                        if (info.size > 0) onOutput(info.presentationTimeUs, read(index))
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
                    }
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> return any
                    else -> any = true // format / buffers changed
                }
            }
            return any
        }

        private fun read(index: Int): Pixels {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val image = codec.getOutputImage(index)
                if (image != null) {
                    try {
                        val crop = image.cropRect
                        val planes = image.planes
                        val y = Plane(planes[0].buffer, planes[0].rowStride, planes[0].pixelStride, crop.left, crop.top)
                        if (alpha) return Pixels(null, y.extractLuma(video.width, video.height))
                        val u = Plane(planes[1].buffer, planes[1].rowStride, planes[1].pixelStride, crop.left / 2, crop.top / 2)
                        val v = Plane(planes[2].buffer, planes[2].rowStride, planes[2].pixelStride, crop.left / 2, crop.top / 2)
                        return Pixels(yuvToArgb(y, u, v, video.width, video.height), null)
                    } finally {
                        image.close()
                    }
                }
            }
            return readLegacy(index)
        }

        @Suppress("DEPRECATION")
        private fun readLegacy(index: Int): Pixels {
            val buffer = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) codec.getOutputBuffer(index)!! else codec.outputBuffers[index])
                    .duplicate()
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val data = buffer.slice()
            val format = codec.outputFormat
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val stride = format.intOrNull("stride")?.takeIf { it >= width } ?: width
            val sliceHeight = format.intOrNull("slice-height")?.takeIf { it >= height } ?: height
            val cropLeft = format.intOrNull("crop-left") ?: 0
            val cropTop = format.intOrNull("crop-top") ?: 0
            val y = Plane(data, stride, 1, cropLeft, cropTop)
            if (alpha) return Pixels(null, y.extractLuma(video.width, video.height))
            val chromaStart = stride * sliceHeight
            val (u, v) = when (format.intOrNull(MediaFormat.KEY_COLOR_FORMAT)) {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> {
                    val chromaStride = stride / 2
                    val vStart = chromaStart + chromaStride * (sliceHeight / 2)
                    Plane(data.sliceFrom(chromaStart), chromaStride, 1, cropLeft / 2, cropTop / 2) to
                            Plane(data.sliceFrom(vStart), chromaStride, 1, cropLeft / 2, cropTop / 2)
                }
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> {
                    Plane(data.sliceFrom(chromaStart), stride, 2, cropLeft / 2, cropTop / 2) to
                            Plane(data.sliceFrom(chromaStart + 1), stride, 2, cropLeft / 2, cropTop / 2)
                }
                else -> throw IOException("Unsupported decoder color format")
            }
            return Pixels(yuvToArgb(y, u, v, video.width, video.height), null)
        }

        fun release() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    /** A plane's bytes; (x, y) addresses the cropped picture, already offset by the crop origin. */
    private class Plane(buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int, private val left: Int, private val top: Int) {
        private val bytes = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }

        fun at(x: Int, y: Int): Int {
            val index = (top + y) * rowStride + (left + x) * pixelStride
            return if (index < bytes.size) bytes[index].toInt() and 0xFF else 0
        }

        fun extractLuma(width: Int, height: Int): ByteArray {
            val out = ByteArray(width * height)
            for (row in 0 until height) {
                for (col in 0 until width) out[row * width + col] = at(col, row).toByte()
            }
            return out
        }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val MAX_STALLS = 500

        private fun ByteBuffer.sliceFrom(offset: Int): ByteBuffer = duplicate().also { it.position(offset.coerceAtMost(limit())) }.slice()

        private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

        // BT.601 limited range, which is what libvpx/ffmpeg produce for Telegram stickers.
        private fun yuvToArgb(y: Plane, u: Plane, v: Plane, width: Int, height: Int): IntArray {
            val out = IntArray(width * height)
            for (row in 0 until height) {
                for (col in 0 until width) {
                    val c = (y.at(col, row) - 16).coerceAtLeast(0) * 298
                    val d = u.at(col / 2, row / 2) - 128
                    val e = v.at(col / 2, row / 2) - 128
                    val r = ((c + 409 * e + 128) shr 8).coerceIn(0, 255)
                    val g = ((c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
                    val b = ((c + 516 * d + 128) shr 8).coerceIn(0, 255)
                    out[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return out
        }

        // Software decoders first: their output layout is the predictable one we can read back.
        @Suppress("DEPRECATION")
        private fun createDecoder(mime: String): MediaCodec {
            val candidates = (0 until MediaCodecList.getCodecCount())
                    .map { MediaCodecList.getCodecInfoAt(it) }
                    .filter { info -> !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
                    .sortedBy { info -> if (info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")) 0 else 1 }
            for (info in candidates) {
                runCatching { return MediaCodec.createByCodecName(info.name) }
            }
            throw IOException("No decoder for $mime")
        }
    }
}
