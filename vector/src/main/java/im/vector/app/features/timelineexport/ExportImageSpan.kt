/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan

class ExportImageSpan(
        private val imageWidth: Int,
        private val imageHeight: Int,
        private val bitmapProvider: () -> Bitmap?,
) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        fm?.apply { ascent = -imageHeight; top = ascent; descent = 0; bottom = 0 }
        return imageWidth
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        bitmapProvider()?.let { image ->
            val scale = minOf(imageWidth.toFloat() / image.width, imageHeight.toFloat() / image.height)
            val width = image.width * scale
            val height = image.height * scale
            canvas.drawBitmap(image, null, RectF(x, y - height, x + width, y.toFloat()), paint)
        }
    }
}
