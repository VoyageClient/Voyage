/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import android.graphics.Bitmap
import im.vector.lib.animatedimage.AnimatedFrame
import im.vector.lib.animatedimage.AnimatedImageFormat
import im.vector.lib.animatedimage.AnimatedImageReader
import timber.log.Timber
import java.io.File
import kotlin.math.sqrt

/**
 * An animated image decoded to a timeline the editor can scrub, the same shape the video path gets
 * from the extractor.
 *
 * Frames are held decoded, which is the only way to jump about at a touch, so they are scaled to
 * fit a memory budget first — a long animation at full size would be tens of megabytes of bitmap,
 * and this fork still runs on devices with a 48 MB heap. The export decodes the original again, so
 * nothing on show here limits the quality of what is sent.
 */
class AnimatedImageSource private constructor(
        val frames: List<Frame>,
        /** The source's own size, not the preview's: everything the editor reports is in these terms. */
        val width: Int,
        val height: Int,
) {

    class Frame(val bitmap: Bitmap, val startUs: Long, val durationUs: Long)

    val durationUs: Long get() = frames.lastOrNull()?.let { it.startUs + it.durationUs } ?: 0L

    val frameRate: Float
        get() = if (durationUs <= 0) DEFAULT_FRAME_RATE else frames.size * 1_000_000f / durationUs

    /** True when the held frames are smaller than the source, so a zoomed-in still is worth decoding afresh. */
    val isDownscaled: Boolean get() = frames.firstOrNull()?.let { it.bitmap.width < width } ?: false

    fun frameAt(us: Long): Bitmap? = indexAt(us)?.let { frames[it].bitmap }

    fun indexAt(us: Long): Int? {
        if (frames.isEmpty()) return null
        // Binary search rather than a scan: this runs once per displayed frame while scrubbing.
        var low = 0
        var high = frames.size - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (frames[middle].startUs <= us) low = middle else high = middle - 1
        }
        return low
    }

    fun release() {
        frames.forEach { it.bitmap.recycle() }
    }

    companion object {
        private const val DEFAULT_FRAME_RATE = 10f

        /** Preview bitmaps are held all at once; this is what they may cost between them. */
        private const val PREVIEW_BUDGET_BYTES = 24 * 1024 * 1024
        private const val BYTES_PER_PIXEL = 4
        private const val MAX_PREVIEW_DIMENSION = 1080

        fun load(file: File, format: AnimatedImageFormat? = null): AnimatedImageSource? {
            val decoded = AnimatedImageReader.readFrames(file, format) ?: return null
            if (decoded.isEmpty()) return null
            val width = decoded[0].bitmap.width
            val height = decoded[0].bitmap.height
            if (width <= 0 || height <= 0) {
                decoded.forEach { it.bitmap.recycle() }
                return null
            }
            return AnimatedImageSource(scaleForPreview(decoded, width, height), width, height)
        }

        /** Frame [index] alone, at the source's size or as near as [maxPixels] allows. Frames composite, so every earlier one is decoded too. */
        fun decodeFrame(file: File, format: AnimatedImageFormat?, index: Int, maxPixels: Int, maxDimension: Int): Bitmap? {
            var result: Bitmap? = null
            try {
                AnimatedImageReader.visitFrames(file, format) { frame, frameIndex, _ ->
                    if (frameIndex == index) {
                        val bitmap = frame.bitmap
                        val scale = minOf(
                                sqrt(maxPixels.toFloat() / (bitmap.width.toFloat() * bitmap.height)),
                                maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height),
                                1f,
                        )
                        // The reader recycles its bitmap after the callback, so this has to be a copy.
                        result = (if (scale < 1f) bitmap.scaledBy(scale) else bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false))
                                ?.takeUnless { it === bitmap }
                        throw FrameFound()
                    }
                }
            } catch (_: FrameFound) {
            } catch (error: OutOfMemoryError) {
                Timber.w(error, "Animated: out of memory decoding a full-size frame")
            }
            return result
        }

        private class FrameFound : RuntimeException()

        private fun scaleForPreview(decoded: List<AnimatedFrame>, width: Int, height: Int): List<Frame> {
            val scale = previewScale(decoded.size, width, height)
            var startUs = 0L
            return decoded.map { frame ->
                val bitmap = if (scale < 1f) frame.bitmap.scaledBy(scale) else frame.bitmap
                if (bitmap !== frame.bitmap) frame.bitmap.recycle()
                val durationUs = frame.durationMs * 1000L
                Frame(bitmap, startUs, durationUs).also { startUs += durationUs }
            }
        }

        private fun previewScale(frameCount: Int, width: Int, height: Int): Float {
            val budgetPerFrame = PREVIEW_BUDGET_BYTES.toFloat() / frameCount.coerceAtLeast(1) / BYTES_PER_PIXEL
            val byBudget = sqrt(budgetPerFrame / (width.toFloat() * height))
            val byDimension = MAX_PREVIEW_DIMENSION.toFloat() / maxOf(width, height)
            return minOf(byBudget, byDimension, 1f)
        }

        private fun Bitmap.scaledBy(scale: Float): Bitmap {
            val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
            val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
            return try {
                Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
            } catch (error: OutOfMemoryError) {
                Timber.w(error, "Animated: out of memory scaling a preview frame")
                this
            }
        }
    }
}
