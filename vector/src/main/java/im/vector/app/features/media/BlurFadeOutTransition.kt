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
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory
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
        // Do NOT call invalidateSelf() here (it's a no-op when the callback is momentarily null), but make
        // sure the Handler loop is running: if a RecyclerView recycle stopped it without a setVisible(true),
        // being drawn at all means we're on screen and should keep fading instead of freezing mid-transition.
        scheduleNext()
    }

    override fun run() {
        scheduled = false
        // Always redraw — the tick that crosses durationMs draws the final, blur-free (alpha 0) frame so no
        // faint residual is left behind. Only keep ticking while the fade is still in progress.
        invalidateSelf()
        if (SystemClock.uptimeMillis() - startMs < durationMs) {
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
        // Keep the fade loop running even when made invisible. Glide pauses/clears requests during a fling and
        // can flip this drawable invisible mid-fade; tearing the loop down there left the blur frozen on screen
        // until an unrelated repaint. The loop self-terminates at durationMs, so letting it finish is cheap and
        // guarantees the blur always reaches alpha 0.
        if (visible) scheduleNext()
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

    // The hash decodes off-thread and a fast local load can beat it; without something to fade from,
    // the image would appear in a single frame. Cross-fading, or the placeholder stays as an opaque
    // layer under the image and a transparent picture shows the waiting fill through it.
    private val fallback = DrawableCrossFadeFactory.Builder(durationMs.toInt())
            .setCrossFadeEnabled(true)
            .build()

    override fun build(dataSource: DataSource, isFirstResource: Boolean): Transition<Drawable> =
            Transition<Drawable> { current, adapter ->
                // The waiting state may be wrapped in the shared placeholder, which owns the
                // blurhash rather than being one.
                val placeholder = adapter.currentDrawable as? BlurHashDrawable
                        ?: (adapter.currentDrawable as? MediaPlaceholderDrawable)?.blurHash
                val placeholderBitmap = placeholder?.bitmap
                if (placeholder == null || placeholderBitmap == null) {
                    fallback.build(dataSource, isFirstResource).transition(current, adapter)
                } else {
                    placeholder.markFinished()
                    adapter.setDrawable(BlurFadeOutDrawable(current, placeholderBitmap, durationMs))
                    true
                }
            }
}
