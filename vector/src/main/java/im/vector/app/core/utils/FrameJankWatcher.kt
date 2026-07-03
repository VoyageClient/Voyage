/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.utils

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Logs dropped main-thread frames under the `VectorPerf` tag while perf logging is enabled, so jank
 * moments can be correlated with whatever else the log shows running at that time. A frame gap over
 * [JANK_THRESHOLD_MS] logs `frame.jank NNms`; ordinary frames are silent.
 *
 * Runs off [Choreographer] (API 16+); below that a self-reposting main-Handler tick measures the
 * same thing (a busy main thread delays the tick).
 */
object FrameJankWatcher {

    private const val TAG = "VectorPerf"
    private const val JANK_THRESHOLD_MS = 48L // ~3 missed frames at 60Hz

    private val running = AtomicBoolean(false)

    fun startIfEnabled() {
        if (!PerfTrace.isEnabled) return
        if (!running.compareAndSet(false, true)) return
        val mainLooper = Looper.getMainLooper()
        val start = Runnable {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                ChoreographerWatcher.postCallback()
            } else {
                HandlerWatcher.start()
            }
        }
        if (Looper.myLooper() == mainLooper) {
            start.run()
        } else {
            Handler(mainLooper).post(start)
        }
    }

    // android.util.Log (not Timber): release builds don't plant a logcat tree, and this exists to
    // be captured via `adb logcat -s VectorPerf` on release devices.
    private fun reportJank(gapMs: Long) {
        Log.i(TAG, "frame.jank ${gapMs}ms (uptime ${SystemClock.uptimeMillis()})")
    }

    // Nested holder so Choreographer.FrameCallback (API 16) is never linked on older devices:
    // referencing it from this object's <clinit> crashes Dalvik on ICS with NoClassDefFoundError.
    private object ChoreographerWatcher {

        private var lastFrameMs = 0L

        private val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!PerfTrace.isEnabled) {
                    running.set(false)
                    lastFrameMs = 0L
                    return
                }
                val nowMs = frameTimeNanos / 1_000_000
                val last = lastFrameMs
                if (last != 0L) {
                    val gap = nowMs - last
                    if (gap >= JANK_THRESHOLD_MS) {
                        reportJank(gap)
                    }
                }
                lastFrameMs = nowMs
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        fun postCallback() {
            lastFrameMs = 0L
            Choreographer.getInstance().postFrameCallback(callback)
        }
    }

    private object HandlerWatcher {

        private const val INTERVAL_MS = 16L
        private var lastTickMs = 0L
        private val handler = Handler(Looper.getMainLooper())

        private val tick = object : Runnable {
            override fun run() {
                if (!PerfTrace.isEnabled) {
                    running.set(false)
                    lastTickMs = 0L
                    return
                }
                val now = SystemClock.uptimeMillis()
                val last = lastTickMs
                if (last != 0L) {
                    // Time beyond the requested delay = how long the main looper was occupied.
                    val gap = now - last - INTERVAL_MS
                    if (gap >= JANK_THRESHOLD_MS) {
                        reportJank(gap)
                    }
                }
                lastTickMs = now
                handler.postDelayed(this, INTERVAL_MS)
            }
        }

        fun start() {
            lastTickMs = 0L
            handler.post(tick)
        }
    }
}
