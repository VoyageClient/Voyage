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
import im.vector.app.core.ui.PerformanceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.max

class BlurHashDrawable private constructor(
        private val intrinsicW: Int,
        private val intrinsicH: Int,
        private val pulse: Boolean,
) : Drawable(), Runnable {

    // Filled once the background decode completes (or immediately from cache). Read on the main thread only.
    var bitmap: Bitmap? = null
        private set

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val startMs = SystemClock.uptimeMillis()
    private var running = false
    private var finished = false

    override fun draw(canvas: Canvas) {
        if (finished) return
        val bmp = bitmap ?: return
        if (!pulse) {
            canvas.drawBitmap(bmp, null, bounds, paint)
            return
        }
        val t = (SystemClock.uptimeMillis() - startMs) % PULSE_PERIOD_MS
        val phase = (t.toDouble() / PULSE_PERIOD_MS) * 2.0 * Math.PI
        val factor = (cos(phase) + 1.0) / 2.0
        val alphaFrac = PULSE_MIN_ALPHA + (1.0 - PULSE_MIN_ALPHA) * factor
        paint.alpha = (255 * alphaFrac).toInt()
        canvas.drawBitmap(bmp, null, bounds, paint)
    }

    private fun onBitmapReady(bmp: Bitmap) {
        if (finished) return
        bitmap = bmp
        if (isVisible && pulse) start()
        invalidateSelf()
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
        if (!pulse || running || bitmap == null) return
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

        // BlurHash decoding is a per-pixel cosine synthesis, too costly to run on the main thread during a
        // scroll. Decode off-thread and cache the result, keyed by hash + decode size, so a recycled/repeated
        // view (same image scrolling back in) reuses the bitmap with no work on the UI thread.
        private val decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val mainHandler = Handler(Looper.getMainLooper())
        private const val CACHE_MAX = 128
        private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > CACHE_MAX
        }

        fun from(hash: String, width: Int?, height: Int?, pulse: Boolean = true): BlurHashDrawable? {
            // In performance mode skip decoding entirely; callers fall back to the solid/neutral placeholder.
            if (PerformanceMode.enabled) return null
            val w = width?.takeIf { it > 0 } ?: DEFAULT_DIM
            val h = height?.takeIf { it > 0 } ?: DEFAULT_DIM
            val scale = DECODE_MAX.toFloat() / max(w, h)
            val decodeW = max(1, (w * scale).toInt())
            val decodeH = max(1, (h * scale).toInt())
            val key = "$hash|$decodeW|$decodeH"
            val drawable = BlurHashDrawable(w, h, pulse)

            val cached = synchronized(cache) { cache[key] }
            if (cached != null) {
                drawable.bitmap = cached
                return drawable
            }
            decodeAsync(hash, decodeW, decodeH, key, drawable)
            return drawable
        }

        private fun decodeAsync(hash: String, decodeW: Int, decodeH: Int, key: String, target: BlurHashDrawable) {
            decodeScope.launch {
                val cached = synchronized(cache) { cache[key] }
                // useCache = false: the library's shared cosine-table cache isn't thread-safe, so concurrent
                // decodes corrupt each other (zig-zag artifacts). Recompute per call — cheap at this size.
                val bmp = cached ?: runCatching { BlurHash.decode(hash, decodeW, decodeH, punch = 1f, useCache = false) }.getOrNull() ?: return@launch
                if (cached == null) synchronized(cache) { cache[key] = bmp }
                mainHandler.post { target.onBitmapReady(bmp) }
            }
        }

        private const val DEFAULT_DIM = 320
        private const val DECODE_MAX = 48
    }
}
