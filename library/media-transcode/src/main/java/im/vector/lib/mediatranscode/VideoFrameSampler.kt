/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.net.Uri
import androidx.annotation.RequiresApi
import im.vector.lib.mediatranscode.gl.OffscreenContext
import im.vector.lib.mediatranscode.gl.OffscreenTarget
import im.vector.lib.mediatranscode.gl.OutputSurface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a range of a video once, front to back, and hands over a frame every [intervalUs] of
 * source time as an upright bitmap of the requested size. Each sampling point takes the first frame
 * at or past it; a frame that covers several (a source slower than the sampling) is handed over once
 * with the count.
 *
 * Blocking, and on one thread throughout since it holds an EGL context. [onFrame] owns the bitmap.
 */
object VideoFrameSampler {

    private const val TIMEOUT_US = 10_000L
    private const val MAX_IDLE_LOOPS = 500
    private const val MATCH_TOLERANCE_US = 1_000L
    private const val BYTES_PER_PIXEL = 4

    fun interface FrameListener {
        fun onFrame(bitmap: Bitmap, samples: Int, samplesDone: Int, samplesTotal: Int)
    }

    /** @return false when the video could not be read or the decoder stalled. */
    @Suppress("DEPRECATION")
    @RequiresApi(18)
    fun sample(
            context: Context,
            uri: Uri,
            source: MediaSourceInfo,
            startUs: Long,
            endUs: Long,
            intervalUs: Long,
            width: Int,
            height: Int,
            listener: FrameListener,
    ): Boolean {
        require(intervalUs > 0 && endUs > startUs && width > 0 && height > 0)
        val rotation = ((source.rotationDegrees % 360) + 360) % 360
        val total = ((endUs - startUs + intervalUs - 1) / intervalUs).toInt()

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var egl: OffscreenContext? = null
        var target: OffscreenTarget? = null
        var outputSurface: OutputSurface? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = extractor.firstTrackOf("video/") ?: return false
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)

            egl = OffscreenContext()
            target = OffscreenTarget(width, height).apply { setup() }
            // Top and bottom swapped: glReadPixels hands back the bottom row first, so drawing upside
            // down reads back upright without a second bitmap per frame.
            outputSurface = OutputSurface(CropGeometry.textureCoords(floatArrayOf(0f, 1f, 1f, 0f), rotation), width, height)
            decoder = MediaCodec.createDecoderByType(source.videoMime).apply {
                configure(format.withoutRotation(), outputSurface.surface, null, 0)
                start()
            }
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val pixels = ByteBuffer.allocateDirect(width * height * BYTES_PER_PIXEL).order(ByteOrder.nativeOrder())
            val inputBuffers = decoder.inputBuffers
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idleLoops = 0
            var done = 0
            while (done < total) {
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
                    if (++idleLoops >= MAX_IDLE_LOOPS) return false
                    continue
                }
                idleLoops = 0
                val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                var covered = 0
                if (info.size > 0) {
                    while (done + covered < total && startUs + (done + covered) * intervalUs <= info.presentationTimeUs + MATCH_TOLERANCE_US) {
                        covered++
                    }
                }
                decoder.releaseOutputBuffer(outputIndex, covered > 0)
                if (covered > 0) {
                    if (!outputSurface.awaitNewImage()) return false
                    target.bind()
                    outputSurface.drawImage()
                    target.readInto(pixels)
                    target.unbind()
                    val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    frame.copyPixelsFromBuffer(pixels)
                    frame.setHasAlpha(false)
                    done += covered
                    listener.onFrame(frame, covered, done, total)
                }
                if (endOfStream) break
            }
            return done > 0
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { outputSurface?.release() }
            runCatching { target?.release() }
            runCatching { egl?.release() }
            extractor.release()
        }
    }
}
