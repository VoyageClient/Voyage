/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.os.Build
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import kotlin.math.sqrt

/**
 * Fit-center with a pixel cap: RecordingCanvas throws on any non-hardware bitmap over 100 MiB, and
 * before Oreo the pixels also live on the Java heap.
 */
internal object ViewerDownsample : DownsampleStrategy() {

    private const val BYTES_PER_PIXEL = 4
    private const val MAX_BYTES = 64L * 1024 * 1024

    private val maxPixels: Long by lazy {
        val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            MAX_BYTES
        } else {
            minOf(MAX_BYTES, Runtime.getRuntime().maxMemory() / 4)
        }
        bytes / BYTES_PER_PIXEL
    }

    override fun getScaleFactor(sourceWidth: Int, sourceHeight: Int, requestedWidth: Int, requestedHeight: Int): Float {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1f
        val fit = minOf(1f, requestedWidth.toFloat() / sourceWidth, requestedHeight.toFloat() / sourceHeight)
        val pixels = sourceWidth.toLong() * sourceHeight
        val budget = if (pixels > maxPixels) sqrt(maxPixels.toDouble() / pixels).toFloat() else 1f
        return minOf(fit, budget)
    }

    // Before KitKat Glide can only apply the power-of-two sample size, so QUALITY could overshoot the budget up to 4x.
    override fun getSampleSizeRounding(sourceWidth: Int, sourceHeight: Int, requestedWidth: Int, requestedHeight: Int): SampleSizeRounding =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) SampleSizeRounding.QUALITY else SampleSizeRounding.MEMORY

    // Decoders other than Downsampler never consult the strategy, so ours check for it themselves.
    fun appliesTo(options: Options): Boolean = options.get(OPTION) === this

    /** For decoders that take an exact output size. */
    fun bounded(width: Int, height: Int): Pair<Int, Int> {
        val scale = getScaleFactor(width, height, width, height)
        if (scale >= 1f) return width to height
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    /** For decoders that only subsample: the smallest power of two that brings the frame within budget. */
    fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while ((width / sample).toLong() * (height / sample) > maxPixels) sample *= 2
        return sample
    }
}
