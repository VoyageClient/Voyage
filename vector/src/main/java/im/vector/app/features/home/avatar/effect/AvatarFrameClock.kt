/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar.effect

import android.os.Handler
import android.os.Looper
import androidx.annotation.UiThread
import androidx.annotation.VisibleForTesting
import im.vector.app.core.utils.DeviceCapabilities
import java.lang.ref.WeakReference

/**
 * The single ticker every animated avatar shares. One [Handler] loop for the whole app, not one per
 * drawable, and it only runs while something visible is registered.
 *
 * The frame number is global, so avatars are in step with each other and a drawable's phase does not
 * depend on when it happened to be bound.
 */
object AvatarFrameClock {

    /** Frames since the process started, in [AvatarEffect.FRAME_DELAY_MS] steps. */
    @Volatile
    var frame: Int = 0
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val subscribers = ArrayList<WeakReference<AnimatedAvatarDrawable>>()
    private var running = false

    private val tick = Runnable { tick() }

    @UiThread
    fun subscribe(drawable: AnimatedAvatarDrawable) {
        compact()
        if (subscribers.none { it.get() === drawable }) {
            subscribers.add(WeakReference(drawable))
        }
        if (!running) {
            running = true
            handler.postDelayed(tick, interval())
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

    /**
     * A fixed rate, with no throttling of its own. Rendering happens off the main thread and a
     * drawable skips a tick while its last frame is still in flight, so each animates as fast as it
     * can be drawn and a device that cannot keep up drops frames rather than blocking anything.
     */
    private fun interval() = AvatarEffect.FRAME_DELAY_MS

    private fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    private fun tick() {
        frame++
        compact()
        var animating = 0
        for (ref in subscribers) {
            val drawable = ref.get() ?: continue
            if (!drawable.wantsFrames()) continue
            // Chosen per tick rather than when subscribing, so a slot freeing up is taken on the
            // next one. Refusing at subscription time strands whatever arrived while another
            // screen's avatars still held the slots, since nothing would start it again.
            if (animating >= maxAnimating && !drawable.exemptFromCap) continue
            drawable.tick()
            animating++
        }
        if (subscribers.isEmpty()) stop() else handler.postDelayed(tick, interval())
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
}
