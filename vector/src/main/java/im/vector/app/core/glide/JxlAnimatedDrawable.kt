/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import com.awxkee.jxlcoder.animation.AnimatedFrameStore
import timber.log.Timber

/**
 * Plays a JPEG XL animation, in place of jxl-coder's own AnimatedDrawable.
 *
 * Its drawable cannot be resumed once stopped — stopping clears the state its setVisible(true)
 * consults — and Glide stops the drawable whenever a view is cleared, while handing that same
 * instance to every view showing the media from its memory cache. Recycling one row therefore froze
 * the animation everywhere, permanently. This keeps stop/start symmetric: stopping only cancels the
 * pending frame, so any later start, or the view becoming visible again, picks the animation back up.
 */
class JxlAnimatedDrawable(
        private val store: AnimatedFrameStore,
) : Drawable(), Animatable, Runnable {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val frameCount = store.framesCount.coerceAtLeast(1)

    private var frameIndex = 0
    private var frame: Bitmap? = null
    private var running = false

    // Decoding a JPEG XL frame is far too costly to do between two frames on the main thread, so the
    // next one is prepared in the background while the current one is on screen.
    private var nextFrame: Bitmap? = null
    private var decoding = false

    init {
        frame = frameAt(0)
        prefetch(1)
    }

    override fun draw(canvas: Canvas) {
        val bitmap = frame ?: frameAt(frameIndex)?.also { frame = it } ?: return
        canvas.drawBitmap(bitmap, null, bounds, paint)
    }

    override fun start() {
        if (running || frameCount <= 1) return
        running = true
        scheduleNext()
    }

    override fun stop() {
        // Only the pending frame is dropped. Nothing here may leave the drawable unable to resume:
        // that is the whole reason this exists rather than the library's own implementation.
        running = false
        handler.removeCallbacks(this)
    }

    override fun isRunning(): Boolean = running

    override fun run() {
        if (!running) return
        val ready = nextFrame
        if (ready == null) {
            // The decode has not caught up; hold this frame rather than blocking the main thread,
            // and look again shortly.
            prefetch((frameIndex + 1) % frameCount)
            handler.postDelayed(this, MIN_FRAME_MS.toLong())
            return
        }
        nextFrame = null
        frameIndex = (frameIndex + 1) % frameCount
        frame = ready
        invalidateSelf()
        prefetch((frameIndex + 1) % frameCount)
        scheduleNext()
    }

    private fun prefetch(index: Int) {
        if (decoding || frameCount <= 1) return
        decoding = true
        decodeExecutor.execute {
            val decoded = frameAt(index)
            handler.post {
                decoding = false
                nextFrame = decoded
            }
        }
    }

    private fun scheduleNext() {
        handler.removeCallbacks(this)
        handler.postDelayed(this, store.getFrameDuration(frameIndex).coerceAtLeast(MIN_FRAME_MS).toLong())
    }

    private fun frameAt(index: Int): Bitmap? = try {
        store.getFrame(index)
    } catch (t: Throwable) {
        Timber.w(t, "Unable to decode JPEG XL frame $index")
        null
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) {
            if (restart) frameIndex = 0
            start()
        } else {
            stop()
        }
        return changed
    }

    override fun getIntrinsicWidth(): Int = store.width

    override fun getIntrinsicHeight(): Int = store.height

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        invalidateSelf()
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        // Frame durations of 0 appear in the wild; without a floor they would spin the handler.
        private const val MIN_FRAME_MS = 20

        // One shared worker: several animations on screen should queue rather than each taking a
        // thread, and decode order does not matter beyond staying off the main thread.
        private val decodeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "jxl-animation").apply { priority = Thread.MIN_PRIORITY }
        }
    }
}
