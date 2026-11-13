/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import android.graphics.Bitmap
import android.os.SystemClock
import im.vector.lib.animatedimage.AnimatedImageFormat
import im.vector.lib.animatedimage.AnimatedImageReader
import timber.log.Timber
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.sqrt

/**
 * An animated image decoded to a timeline the editor can scrub, the same shape the video path gets
 * from the extractor.
 *
 * Frames are held decoded, which is the only way to jump about at a touch, so they are scaled to
 * fit a memory budget first — a long animation at full size would be tens of megabytes of bitmap,
 * and this fork still runs on devices with a 48 MB heap. The export decodes the original again, so
 * nothing on show here limits the quality of what is sent.
 *
 * The timeline comes from the header, so the source is usable while its frames are still decoding.
 */
class AnimatedImageSource private constructor(
        /** The source's own size, not the preview's: everything the editor reports is in these terms. */
        val width: Int,
        val height: Int,
        private val startsUs: LongArray,
        val durationUs: Long,
        /** True when the held frames are smaller than the source, so a zoomed-in still is worth decoding afresh. */
        val isDownscaled: Boolean,
) {

    private val bitmaps = arrayOfNulls<Bitmap>(startsUs.size)
    private var loaded = 0
    private var released = false

    val frameCount: Int get() = startsUs.size

    val frameRate: Float
        get() = if (durationUs <= 0) DEFAULT_FRAME_RATE else frameCount * 1_000_000f / durationUs

    /** Only the frame due at [us] itself, so null while that one is still decoding. */
    @Synchronized
    fun frameAt(us: Long): Bitmap? = indexAt(us)?.takeIf { it < loaded }?.let { bitmaps[it] }

    /** Frame [index], or while it is still decoding the latest one before it that is in. */
    @Synchronized
    fun frameAtOrBefore(index: Int): Bitmap? = if (loaded == 0) null else bitmaps[minOf(index, loaded - 1)]

    fun indexAt(us: Long): Int? {
        if (startsUs.isEmpty()) return null
        // Binary search rather than a scan: this runs once per displayed frame while scrubbing.
        var low = 0
        var high = startsUs.size - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (startsUs[middle] <= us) low = middle else high = middle - 1
        }
        return low
    }

    @Synchronized
    private fun add(bitmap: Bitmap): Boolean {
        if (released || loaded >= bitmaps.size) {
            bitmap.recycle()
            return false
        }
        bitmaps[loaded++] = bitmap
        return true
    }

    @Synchronized
    fun release() {
        released = true
        bitmaps.forEachIndexed { index, bitmap ->
            bitmap?.recycle()
            bitmaps[index] = null
        }
    }

    companion object {
        private const val DEFAULT_FRAME_RATE = 10f

        /** Preview bitmaps are held all at once; this is what they may cost between them. */
        private const val PREVIEW_BUDGET_BYTES = 24 * 1024 * 1024
        private const val BYTES_PER_PIXEL = 4
        private const val MAX_PREVIEW_DIMENSION = 1080

        /**
         * Each frame is scaled as it is decoded, so the full-size frames are never all held at once.
         * [onReady] is called on this thread once the first frame is in, long before the rest are;
         * the source returned at the end is the same one, now complete. A failure releases it.
         */
        fun load(
                file: File,
                format: AnimatedImageFormat? = null,
                isCancelled: () -> Boolean = { false },
                onReady: (AnimatedImageSource) -> Unit,
        ): AnimatedImageSource? {
            var source: AnimatedImageSource? = null
            var scale = 1f
            val startedAt = SystemClock.elapsedRealtime()
            var firstFrameMs = 0L
            val onHeader = { width: Int, height: Int, durationsMs: IntArray ->
                if (width <= 0 || height <= 0 || durationsMs.isEmpty()) throw IllegalStateException("Empty animation")
                scale = previewScale(durationsMs.size, width, height)
                var startUs = 0L
                val startsUs = LongArray(durationsMs.size) { index -> startUs.also { startUs += durationsMs[index] * 1000L } }
                source = AnimatedImageSource(width, height, startsUs, startUs, scale < 1f)
                scale
            }
            val decoded = try {
                AnimatedImageReader.visitFrames(file, format, onHeader) { frame, index, _ ->
                    val target = source ?: throw IllegalStateException("Frame before header")
                    if (isCancelled()) throw CancellationException()
                    // The reader recycles its bitmap after the callback, so even an unscaled frame is a copy.
                    val bitmap = frame.bitmap.resizedCopy(target.width * scale, target.height * scale)
                            ?: throw OutOfMemoryError("Animated: no room for a preview frame")
                    if (!target.add(bitmap)) throw CancellationException()
                    if (index == 0) {
                        firstFrameMs = SystemClock.elapsedRealtime() - startedAt
                        onReady(target)
                    }
                }
            } catch (error: Throwable) {
                if (error !is CancellationException) Timber.w(error, "Animated: cannot load frames")
                false
            }
            val result = source
            if (!decoded || result == null || result.loaded < result.frameCount) {
                result?.release()
                return null
            }
            Timber.d(
                    "Animated: loaded ${result.frameCount} frames of ${result.width}x${result.height} at scale $scale " +
                            "in ${SystemClock.elapsedRealtime() - startedAt} ms, first after $firstFrameMs ms"
            )
            return result
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
                        result = bitmap.resizedCopy(bitmap.width * scale, bitmap.height * scale)
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

        private fun previewScale(frameCount: Int, width: Int, height: Int): Float {
            val budgetPerFrame = PREVIEW_BUDGET_BYTES.toFloat() / frameCount.coerceAtLeast(1) / BYTES_PER_PIXEL
            val byBudget = sqrt(budgetPerFrame / (width.toFloat() * height))
            val byDimension = MAX_PREVIEW_DIMENSION.toFloat() / maxOf(width, height)
            return minOf(byBudget, byDimension, 1f)
        }

        /** Always a new bitmap, never this one. */
        private fun Bitmap.resizedCopy(targetWidth: Float, targetHeight: Float): Bitmap? {
            val scaledWidth = targetWidth.toInt().coerceAtLeast(1)
            val scaledHeight = targetHeight.toInt().coerceAtLeast(1)
            return try {
                if (scaledWidth == width && scaledHeight == height) {
                    copy(config ?: Bitmap.Config.ARGB_8888, false)
                } else {
                    Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
                }
            } catch (error: OutOfMemoryError) {
                Timber.w(error, "Animated: out of memory scaling a preview frame")
                null
            }
        }
    }
}
