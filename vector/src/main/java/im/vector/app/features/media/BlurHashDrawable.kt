/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.vanniktech.blurhash.BlurHash
import kotlin.math.cos
import kotlin.math.max

class BlurHashDrawable private constructor(
        val bitmap: Bitmap,
        private val intrinsicW: Int,
        private val intrinsicH: Int,
        private val pulse: Boolean,
) : Drawable(), Runnable {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val startMs = SystemClock.uptimeMillis()
    private var running = false
    private var finished = false

    override fun draw(canvas: Canvas) {
        if (finished) return
        if (!pulse) {
            canvas.drawBitmap(bitmap, null, bounds, paint)
            return
        }
        val t = (SystemClock.uptimeMillis() - startMs) % PULSE_PERIOD_MS
        val phase = (t.toDouble() / PULSE_PERIOD_MS) * 2.0 * Math.PI
        val factor = (cos(phase) + 1.0) / 2.0
        val alphaFrac = PULSE_MIN_ALPHA + (1.0 - PULSE_MIN_ALPHA) * factor
        paint.alpha = (255 * alphaFrac).toInt()
        canvas.drawBitmap(bitmap, null, bounds, paint)
    }

    fun markFinished() {
        finished = true
        stop()
        invalidateSelf()
    }

    fun scheduleFinish(delayMs: Long) {
        handler.postDelayed({ markFinished() }, delayMs)
    }

    override fun getIntrinsicWidth(): Int = intrinsicW
    override fun getIntrinsicHeight(): Int = intrinsicH
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) start() else stop()
        return changed
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (isVisible) start()
    }

    private fun start() {
        if (!pulse || running) return
        running = true
        handler.post(this)
    }

    private fun stop() {
        running = false
        handler.removeCallbacks(this)
    }

    override fun run() {
        if (!running) return
        invalidateSelf()
        handler.postDelayed(this, FRAME_INTERVAL_MS)
    }

    companion object {
        private const val PULSE_PERIOD_MS = 1750L
        private const val PULSE_MIN_ALPHA = 0.65
        private const val FRAME_INTERVAL_MS = 16L

        fun from(hash: String, width: Int?, height: Int?, pulse: Boolean = true): BlurHashDrawable? {
            val w = width?.takeIf { it > 0 } ?: DEFAULT_DIM
            val h = height?.takeIf { it > 0 } ?: DEFAULT_DIM
            val scale = DECODE_MAX.toFloat() / max(w, h)
            val decodeW = max(1, (w * scale).toInt())
            val decodeH = max(1, (h * scale).toInt())
            // useCache = false: the library caches cosine tables in a shared singleton that isn't
            // thread-safe, so concurrent decodes (multiple images scrolling in) corrupt each other and
            // produce intermittent zig-zag artifacts. Recomputing per call is cheap at this size.
            val bitmap = runCatching { BlurHash.decode(hash, decodeW, decodeH, punch = 1f, useCache = false) }.getOrNull() ?: return null
            return BlurHashDrawable(bitmap, w, h, pulse)
        }

        private const val DEFAULT_DIM = 320
        private const val DECODE_MAX = 48
    }
}
