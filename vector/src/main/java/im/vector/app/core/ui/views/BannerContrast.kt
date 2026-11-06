/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import kotlin.math.max
import kotlin.math.roundToInt

internal class BannerContrast(val pixels: IntArray) {
    private val luminance = DoubleArray(pixels.size) { luminance(pixels[it]) }.apply { sort() }
    val whiteCoverage = coverage { it <= MAX_WHITE_BACKGROUND }
    val darkCoverage = coverage { it >= MIN_DARK_BACKGROUND }
    val lowerWhite = if (luminance.isEmpty()) 1.0 else 1.05 / (luminance[luminance.lastIndex - lowerIndex()] + 0.05)
    val lowerLuminance = if (luminance.isEmpty()) 0.0 else luminance[lowerIndex()]
    val medianLuminance = if (luminance.isEmpty()) 0.0 else luminance[luminance.size / 2]
    val prefersDark = whiteCoverage <= 0.1 && darkCoverage >= 0.95 && lowerLuminance >= 0.4 && medianLuminance >= 0.55
    val retainsDark = whiteCoverage < 0.25 && darkCoverage >= 0.9 && lowerLuminance >= 0.25 && medianLuminance >= 0.4
    val needsShadow = whiteCoverage < 1.0 || lowerWhite < 7.0

    private inline fun coverage(matches: (Double) -> Boolean) = if (luminance.isEmpty()) 0.0 else luminance.count(matches).toDouble() / luminance.size

    private fun lowerIndex() = ((luminance.size - 1) * 0.1).toInt()

    companion object {
        const val WHITE = Color.WHITE
        const val DARK = 0xFF15191E.toInt()
        private const val MAX_WHITE_BACKGROUND = 1.05 / 3.0 - 0.05
        private val linearChannels = DoubleArray(256) {
            val channel = it / 255.0
            if (channel <= 0.04045) channel / 12.92 else Math.pow((channel + 0.055) / 1.055, 2.4)
        }
        private val MIN_DARK_BACKGROUND = 3.0 * (luminance(DARK) + 0.05) - 0.05

        private fun luminance(color: Int) = 0.2126 * linearChannels[(color ushr 16) and 255] +
                0.7152 * linearChannels[(color ushr 8) and 255] + 0.0722 * linearChannels[color and 255]
    }
}

internal class StableBannerColor {
    var dark = false
        private set

    fun choose(regions: List<BannerContrast>, reset: Boolean = false): Boolean {
        dark = regions.isNotEmpty() && regions.all { if (dark && !reset) it.retainsDark else it.prefersDark }
        return dark
    }
}

internal class BannerImageSampler(private val background: Int) {
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private var viewWidth = 0
    private var viewHeight = 0
    private var source: Bitmap? = null
    private val sourceBounds = Rect()
    private val inverse = Matrix()
    private val coordinates = FloatArray(9)
    private var imageBackground = background

    fun render(view: ImageView): Boolean {
        if (view.width <= 0 || view.height <= 0 || view.drawable == null) return false
        viewWidth = view.width
        viewHeight = view.height
        val drawable = view.drawable
        val backdrop = view.background
        val readable = drawable is BitmapDrawable &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || drawable.bitmap.config != Bitmap.Config.HARDWARE)
        if (readable && drawable is BitmapDrawable && !drawable.bounds.isEmpty &&
                view.paddingLeft == 0 && view.paddingTop == 0 && view.paddingRight == 0 && view.paddingBottom == 0 &&
                view.scrollX == 0 && view.scrollY == 0 && drawable.gravity == Gravity.FILL &&
                drawable.paint.alpha == 255 && drawable.paint.colorFilter == null && drawable.paint.shader == null &&
                (backdrop == null || backdrop is ColorDrawable) && view.imageMatrix.invert(inverse)) {
            source = drawable.bitmap
            sourceBounds.set(drawable.bounds)
            inverse.getValues(coordinates)
            imageBackground = if (backdrop is ColorDrawable) ColorUtils.compositeColors(backdrop.color, background) else background
            return true
        }
        source = null
        val scale = minOf(1f, MAX_DIMENSION.toFloat() / max(view.width, view.height))
        val width = max(1, (view.width * scale).roundToInt())
        val height = max(1, (view.height * scale).roundToInt())
        if (bitmap?.width != width || bitmap?.height != height) {
            release()
            bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
            canvas = Canvas(bitmap!!)
        }
        viewWidth = view.width
        viewHeight = view.height
        bitmap!!.eraseColor(background)
        val target = canvas!!
        val save = target.save()
        target.scale(width.toFloat() / view.width, height.toFloat() / view.height)
        try {
            view.draw(target)
        } finally {
            target.restoreToCount(save)
        }
        return true
    }

    fun sample(rect: RectF): BannerContrast {
        val image = source ?: bitmap ?: return BannerContrast(IntArray(0))
        return BannerContrast(IntArray(SAMPLE_GRID * SAMPLE_GRID) { index ->
            val x = rect.left + rect.width() * (index % SAMPLE_GRID + 0.5f) / SAMPLE_GRID
            val y = rect.top + rect.height() * (index / SAMPLE_GRID + 0.5f) / SAMPLE_GRID
            if (x < 0 || x >= viewWidth || y < 0 || y >= viewHeight) {
                background
            } else if (source != null) {
                val divisor = coordinates[6] * x + coordinates[7] * y + coordinates[8]
                val drawableX = (coordinates[0] * x + coordinates[1] * y + coordinates[2]) / divisor
                val drawableY = (coordinates[3] * x + coordinates[4] * y + coordinates[5]) / divisor
                if (!drawableX.isFinite() || !drawableY.isFinite() || drawableX < sourceBounds.left || drawableX >= sourceBounds.right ||
                        drawableY < sourceBounds.top || drawableY >= sourceBounds.bottom) {
                    imageBackground
                } else {
                    val pixel = image[
                            ((drawableX - sourceBounds.left) * image.width / sourceBounds.width()).toInt().coerceIn(0, image.width - 1),
                            ((drawableY - sourceBounds.top) * image.height / sourceBounds.height()).toInt().coerceIn(0, image.height - 1),
                    ]
                    if (Color.alpha(pixel) == 255) pixel else ColorUtils.compositeColors(pixel, imageBackground)
                }
            } else {
                image[
                        (x * image.width / viewWidth).toInt().coerceIn(0, image.width - 1),
                        (y * image.height / viewHeight).toInt().coerceIn(0, image.height - 1),
                ]
            }
        })
    }

    fun release() {
        source = null
        canvas = null
        bitmap?.recycle()
        bitmap = null
    }

    companion object {
        private const val MAX_DIMENSION = 128
        private const val SAMPLE_GRID = 8
    }
}
