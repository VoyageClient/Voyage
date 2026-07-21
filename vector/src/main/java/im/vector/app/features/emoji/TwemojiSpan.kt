/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.emoji

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.style.ReplacementSpan

/**
 * Draws a Twemoji sprite inline, sized to the run's font height and aligned to the baseline, so it
 * sits like a glyph. Used where the platform can't render the emoji itself (pre-KitKat, or when the
 * user opts into Twemoji).
 */
class TwemojiSpan(private val bitmap: Bitmap) : ReplacementSpan() {

    private val dst = Rect()

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val metrics = paint.fontMetricsInt
        if (fm != null) {
            fm.ascent = metrics.ascent
            fm.descent = metrics.descent
            fm.top = metrics.top
            fm.bottom = metrics.bottom
        }
        return metrics.descent - metrics.ascent
    }

    override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
    ) {
        val metrics = paint.fontMetricsInt
        val size = metrics.descent - metrics.ascent
        dst.set(0, 0, size, size)
        canvas.save()
        canvas.translate(x, (y + metrics.ascent).toFloat())
        // Follow the run paint's alpha so faded text (spoiler crossfades, pill fade-ins) fades the sprite too.
        FILTER_PAINT.alpha = paint.alpha
        canvas.drawBitmap(bitmap, null, dst, FILTER_PAINT)
        FILTER_PAINT.alpha = 255
        canvas.restore()
    }

    companion object {
        private val FILTER_PAINT = Paint(Paint.FILTER_BITMAP_FLAG)
    }
}
