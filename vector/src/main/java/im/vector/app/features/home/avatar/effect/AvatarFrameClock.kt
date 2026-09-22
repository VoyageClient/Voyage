/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar.effect

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import androidx.annotation.RequiresApi
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import im.vector.app.core.utils.DeviceCapabilities
import java.lang.ref.WeakReference

/**
 * The single ticker every animated avatar shares. One loop for the whole app, not one per drawable,
 * and it only runs while something visible is registered.
 *
 * The frame number is global, so avatars are in step with each other and a drawable's phase does not
 * depend on when it happened to be bound.
 *
 * Frames are due at a fixed rate rather than a fixed delay from the last one, and a tick that comes
 * up while the main thread is already behind is dropped rather than taken: a shape losing a frame is
 * invisible next to the scroll or room opening that the main thread is busy with.
 */
object AvatarFrameClock {

    /** Frames since the process started, in [AvatarEffect.FRAME_DELAY_MS] steps. */
    @Volatile
    var frame: Int = 0
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val subscribers = ArrayList<WeakReference<AnimatedAvatarDrawable>>()
    private var running = false

    /** When the next avatar frame is due, advanced at a fixed rate rather than by delay from the last. */
    private var nextFrameAt = 0L

    private val tick = Runnable {
        val now = SystemClock.uptimeMillis()
        onDue(now, lateBy = now - nextFrameAt)
        if (running) schedule()
    }

    // A vsync callback carries the frame it belongs to, which makes "the main thread is behind"
    // something the clock can read directly rather than infer from its own lateness. Ice Cream
    // Sandwich has no Choreographer and falls back to a plain delay, which still measures its own
    // lateness against when the frame was due.
    private val vsync = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) VsyncTicker() else null

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN)
    private class VsyncTicker {
        private val callback = Choreographer.FrameCallback { onVsync(it) }
        fun post() = Choreographer.getInstance().postFrameCallback(callback)
        fun cancel() = Choreographer.getInstance().removeFrameCallback(callback)
    }

    @UiThread
    fun subscribe(drawable: AnimatedAvatarDrawable) {
        compact()
        if (subscribers.none { it.get() === drawable }) {
            subscribers.add(WeakReference(drawable))
        }
        if (!running) {
            running = true
            nextFrameAt = SystemClock.uptimeMillis()
            schedule()
        }
    }

    @UiThread
    fun unsubscribe(drawable: AnimatedAvatarDrawable) {
        subscribers.removeAll { it.get() == null || it.get() === drawable }
        if (subscribers.isEmpty()) stop()
    }

    /** Called when the last activity pauses: a backgrounded process should not be painting avatars. */
    @UiThread
    fun pause() {
        stop()
        // Take the list away first: stopping a drawable unsubscribes it, which edits the very list
        // being walked.
        val stopping = subscribers.mapNotNull { it.get() }
        subscribers.clear()
        stopping.forEach { it.stop() }
    }

    private fun stop() {
        running = false
        handler.removeCallbacks(tick)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) vsync?.cancel()
    }

    private fun schedule() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN && vsync != null) {
            vsync.post()
        } else {
            handler.postDelayed(tick, (nextFrameAt - SystemClock.uptimeMillis()).coerceIn(1, AvatarEffect.FRAME_DELAY_MS))
        }
    }

    private fun onVsync(frameTimeNanos: Long) {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        // The gap between the vsync this callback belongs to and actually running it.
        onDue(now, lateBy = now - frameTimeNanos / 1_000_000)
        if (running) schedule()
    }

    @VisibleForTesting
    @UiThread
    internal fun onDue(now: Long, lateBy: Long) {
        when {
            lateBy > JANK_MS -> {
                // Not caught up afterwards: a frame given up is gone, and the shapes carry on from
                // wherever the clock has reached.
                nextFrameAt = now + AvatarEffect.FRAME_DELAY_MS
            }
            now >= nextFrameAt -> {
                nextFrameAt = maxOf(nextFrameAt + AvatarEffect.FRAME_DELAY_MS, now + 1)
                tick()
            }
            // A freshly bound avatar is showing nothing, so it does not wait for the animation's own
            // rate — only for a frame the main thread can spare.
            else -> tick(hungryOnly = true)
        }
    }

    /** @param hungryOnly serve only the drawables with nothing rendered yet, without advancing time. */
    private fun tick(hungryOnly: Boolean = false) {
        if (!hungryOnly) frame++
        compact()
        var animating = 0
        for (ref in subscribers) {
            val drawable = ref.get() ?: continue
            if (!drawable.wantsFrames()) continue
            // A first frame is one render, not an animation, so the cap does not apply to it: an
            // avatar the cap holds back should still be sharp rather than stuck on its inline frame.
            if (hungryOnly) {
                if (drawable.needsFirstFrame()) drawable.tick()
                continue
            }
            // Chosen per tick rather than when subscribing, so a slot freeing up is taken on the
            // next one. Refusing at subscription time strands whatever arrived while another
            // screen's avatars still held the slots, since nothing would start it again.
            if (animating >= maxAnimating && !drawable.exemptFromCap) continue
            animating++
            drawable.tick()
        }
        if (subscribers.isEmpty()) stop()
    }

    /** How many drawables are being given frames right now. */
    @VisibleForTesting
    @UiThread
    internal fun animatingNow(): Int {
        var animating = 0
        for (ref in subscribers) {
            val drawable = ref.get() ?: continue
            if (!drawable.wantsFrames()) continue
            if (animating >= maxAnimating && !drawable.exemptFromCap) continue
            animating++
        }
        return animating
    }

    private fun compact() {
        subscribers.removeAll { it.get() == null }
    }

    private val maxAnimating: Int
        get() = if (DeviceCapabilities.isLowPerformanceHardware) MAX_ANIMATING_LOW_END else MAX_ANIMATING

    // A timeline and a room list together pass sixteen visible avatars easily, and one held still
    // beside moving neighbours looks broken. Rendering is off the main thread and each drawable skips
    // ticks it cannot keep up with, so this is really a bound on how many frame buffers exist at once.
    private const val MAX_ANIMATING = 32
    private const val MAX_ANIMATING_LOW_END = 6

    // Two frames at 60Hz: enough slack that an ordinary frame is not mistaken for jank, small enough
    // that a bind storm is.
    private const val JANK_MS = 32
}
