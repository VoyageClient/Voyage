/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaExtractor
import android.net.Uri
import androidx.annotation.RequiresApi
import im.vector.lib.mediatranscode.gl.OffscreenContext
import im.vector.lib.mediatranscode.gl.OffscreenTarget
import im.vector.lib.mediatranscode.gl.OutputSurface
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * One exact frame of a video as a bitmap, upright. MediaMetadataRetriever is no substitute: on some
 * devices it ignores OPTION_CLOSEST and hands back the nearest sync frame for every time asked, so
 * this decodes from that sync frame up to the one wanted, through the same GL stage the export uses.
 * That stage applies the decoder's alignment crop, which a copy of the preview surface does not, and
 * the container's rotation.
 *
 * Blocking; run it off the main thread, and on one thread throughout, since it holds an EGL context.
 */
object VideoFrameGrabber {

    private const val TIMEOUT_US = 10_000L
    private const val MAX_IDLE_LOOPS = 500

    /** Two copies are held at once (the read-back and the bitmap), so this keeps a 4K frame to about 64 MB. */
    private const val MAX_PIXELS = 8_294_400L

    /** Within this of [grab]'s time counts as the frame asked for: players and extractors round differently. */
    private const val MATCH_TOLERANCE_US = 1_000L

    @RequiresApi(18)
    fun grab(context: Context, uri: Uri, positionUs: Long): Bitmap? {
        val source = MediaSourceInfo.probe(context, uri) ?: return null
        val rotation = ((source.rotationDegrees % 360) + 360) % 360
        val budget = minOf(MAX_PIXELS, Runtime.getRuntime().maxMemory() / 4 / BYTES_PER_PIXEL)
        val shrink = minOf(1.0, sqrt(budget.toDouble() / (source.displayWidth.toLong() * source.displayHeight)))
        val width = (source.displayWidth * shrink).toInt().coerceAtLeast(1)
        val height = (source.displayHeight * shrink).toInt().coerceAtLeast(1)

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var egl: OffscreenContext? = null
        var target: OffscreenTarget? = null
        var outputSurface: OutputSurface? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = extractor.firstTrackOf("video/") ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)

            egl = OffscreenContext()
            target = OffscreenTarget(width, height).apply { setup() }
            outputSurface = OutputSurface(CropGeometry.textureCoords(floatArrayOf(0f, 0f, 1f, 1f), rotation), width, height)
            decoder = MediaCodec.createDecoderByType(source.videoMime).apply {
                configure(format.withoutRotation(), outputSurface.surface, null, 0)
                start()
            }
            extractor.seekTo(positionUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            if (!decodeUpTo(extractor, decoder, outputSurface, positionUs)) return null

            target.bind()
            outputSurface.drawImage()
            val pixels = ByteBuffer.allocateDirect(width * height * BYTES_PER_PIXEL).order(ByteOrder.nativeOrder())
            target.readInto(pixels)
            target.unbind()
            val readBack = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            readBack.copyPixelsFromBuffer(pixels)
            // GL reads the bottom row first.
            val upright = Bitmap.createBitmap(readBack, 0, 0, width, height, Matrix().apply { preScale(1f, -1f) }, false)
            if (upright !== readBack) readBack.recycle()
            return upright
        } catch (error: Exception) {
            Timber.w(error, "VideoFrameGrabber: could not decode the frame at ${positionUs}us")
            return null
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { outputSurface?.release() }
            runCatching { target?.release() }
            runCatching { egl?.release() }
            extractor.release()
        }
    }

    /**
     * Feeds from the sync frame and lets every frame before the wanted one go undrawn. Renders the
     * first frame at or past it into [outputSurface] and returns true; false when the stream ends first.
     */
    @Suppress("DEPRECATION")
    @RequiresApi(18)
    private fun decodeUpTo(extractor: MediaExtractor, decoder: MediaCodec, outputSurface: OutputSurface, positionUs: Long): Boolean {
        val inputBuffers = decoder.inputBuffers
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var idleLoops = 0
        while (idleLoops < MAX_IDLE_LOOPS) {
            if (!inputDone) {
                val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val size = extractor.readSampleData(inputBuffers[inputIndex].apply { clear() }, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outputIndex < 0) {
                idleLoops++
                continue
            }
            idleLoops = 0
            val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val wanted = info.size > 0 && info.presentationTimeUs >= positionUs - MATCH_TOLERANCE_US
            decoder.releaseOutputBuffer(outputIndex, wanted)
            if (wanted) return outputSurface.awaitNewImage()
            if (endOfStream) return false
        }
        return false
    }

    private const val BYTES_PER_PIXEL = 4
}
