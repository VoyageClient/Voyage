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
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.request.transition.Transition
import com.bumptech.glide.request.transition.TransitionFactory

class BlurFadeOutDrawable(
        private val image: Drawable,
        private val blurBitmap: Bitmap,
        private val durationMs: Long,
) : Drawable(), Drawable.Callback, Runnable {

    private val startMs = SystemClock.uptimeMillis()
    private val blurPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private var scheduled = false

    init {
        // Own the wrapped drawable's callback so an animated child's frame invalidations are
        // routed through us to the host view. Without this the child's getCallback() is null and,
        // once the fade ends and we stop self-invalidating, the animation freezes until some
        // unrelated repaint happens.
        image.callback = this
    }

    override fun draw(canvas: Canvas) {
        image.bounds = bounds
        image.draw(canvas)
        val elapsed = SystemClock.uptimeMillis() - startMs
        if (elapsed >= durationMs) return
        val alpha = (255 * (1f - elapsed.toFloat() / durationMs)).toInt().coerceIn(0, 255)
        if (alpha > 0) {
            blurPaint.alpha = alpha
            canvas.drawBitmap(blurBitmap, null, bounds, blurPaint)
        }
        // Do NOT call invalidateSelf() here. If the callback is null (drawable momentarily
        // detached while RecyclerView relayouts) the call is a no-op, and nothing ever
        // reschedules it — permanently stuck. The Handler loop below drives redraws instead.
    }

    override fun run() {
        scheduled = false
        if (SystemClock.uptimeMillis() - startMs < durationMs) {
            invalidateSelf()
            scheduleNext()
        }
    }

    private fun scheduleNext() {
        if (!scheduled && SystemClock.uptimeMillis() - startMs < durationMs) {
            scheduled = true
            handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        image.bounds = bounds
        if (isVisible && !bounds.isEmpty) scheduleNext()
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        image.setVisible(visible, restart)
        val changed = super.setVisible(visible, restart)
        if (visible) scheduleNext() else {
            scheduled = false
            handler.removeCallbacks(this)
        }
        return changed
    }

    override fun setAlpha(alpha: Int) { image.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { image.colorFilter = colorFilter }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = image.intrinsicWidth
    override fun getIntrinsicHeight(): Int = image.intrinsicHeight

    override fun invalidateDrawable(who: Drawable) = invalidateSelf()
    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = scheduleSelf(what, `when`)
    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)

    companion object {
        private const val FRAME_INTERVAL_MS = 16L
    }
}

class BlurFadeOutTransitionFactory(private val durationMs: Long) : TransitionFactory<Drawable> {
    override fun build(dataSource: DataSource, isFirstResource: Boolean): Transition<Drawable> =
            Transition<Drawable> { current, adapter ->
                val placeholder = adapter.currentDrawable as? BlurHashDrawable
                if (placeholder == null) {
                    false
                } else {
                    placeholder.markFinished()
                    adapter.setDrawable(BlurFadeOutDrawable(current, placeholder.bitmap, durationMs))
                    true
                }
            }
}
