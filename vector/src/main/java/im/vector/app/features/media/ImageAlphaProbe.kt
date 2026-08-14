/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache

/**
 * Whether a picture actually draws anything see-through — an alpha channel that is opaque
 * everywhere does not count. Answers where painting a fill behind the picture would show.
 */
object ImageAlphaProbe {

    private val results = LruCache<String, Boolean>(256)

    /**
     * @param key identifies the picture across rebinds; the answer is kept against it.
     * @param cornerFraction how much of each side the rounded corners take. What the display cut
     * away is not the picture being see-through, and every thumbnail here is rounded.
     */
    fun usesAlpha(key: String, drawable: Drawable?, cornerFraction: Float = 0f): Boolean {
        results.get(key)?.let { return it }
        val answer = probe(drawable, cornerFraction) ?: return false
        results.put(key, answer)
        return answer
    }

    /** @return null when there is nothing to look at yet, which is not an answer to remember. */
    private fun probe(drawable: Drawable?, cornerFraction: Float): Boolean? {
        drawable ?: return null
        val bitmap = (drawable as? BitmapDrawable)?.bitmap
        if (bitmap != null && !bitmap.isRecycled) {
            // The flag alone is not the question — plenty of pictures carry a channel they never
            // use — but a picture without one cannot be see-through, and that check is free.
            if (!bitmap.hasAlpha()) return false
            return bitmap.hasTransparentPixel(cornerFraction)
        }
        return drawable.rasterised()?.let {
            val transparent = it.hasTransparentPixel(cornerFraction)
            it.recycle()
            transparent
        }
    }

    /**
     * Anything that is not a plain bitmap — an animated WebP, APNG or GIF — gets one frame drawn
     * small and looked at there. Scaling down averages a thinly transparent edge into its
     * neighbours rather than losing it, so it still reads as not fully opaque.
     */
    private fun Drawable.rasterised(): Bitmap? {
        val width = intrinsicWidth
        val height = intrinsicHeight
        if (width <= 0 || height <= 0) return null
        val scale = (RASTER_MAX_PX.toFloat() / maxOf(width, height)).coerceAtMost(1f)
        val target = Bitmap.createBitmap(
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
        )
        // Borrowed from the view that is drawing it, so it has to go back exactly as it was.
        val previousBounds = Rect(bounds)
        return try {
            setBounds(0, 0, target.width, target.height)
            draw(Canvas(target))
            target
        } catch (e: RuntimeException) {
            target.recycle()
            null
        } finally {
            bounds = previousBounds
        }
    }

    /**
     * Row by row, stopping at the first hole: a picture with any is usually see-through early on.
     * The four corner squares are stepped over, plus a pixel for the antialiasing along their arc.
     */
    private fun Bitmap.hasTransparentPixel(cornerFraction: Float): Boolean {
        val cornerX = cornerSize(width, cornerFraction)
        val cornerY = cornerSize(height, cornerFraction)
        val row = IntArray(width)
        for (y in 0 until height) {
            getPixels(row, 0, width, 0, y, width, 1)
            val rounded = y < cornerY || y >= height - cornerY
            val from = if (rounded) cornerX else 0
            val until = if (rounded) width - cornerX else width
            for (x in from until until) {
                if (row[x] ushr 24 != 0xFF) return true
            }
        }
        return false
    }

    private fun cornerSize(side: Int, fraction: Float): Int {
        if (fraction <= 0f) return 0
        return ((side * fraction).toInt() + 1).coerceAtMost(side / 2)
    }

    private const val RASTER_MAX_PX = 192
}
