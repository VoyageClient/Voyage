/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.utils

import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Logs dropped main-thread frames under the `VectorPerf` tag while perf logging is enabled, so jank
 * moments can be correlated with whatever else the log shows running at that time. A frame gap over
 * [JANK_THRESHOLD_MS] logs `frame.jank NNms`; ordinary frames are silent.
 *
 * Runs off [Choreographer] (API 16+); on older devices it silently does nothing.
 */
object FrameJankWatcher {

    private const val TAG = "VectorPerf"
    private const val JANK_THRESHOLD_MS = 48L // ~3 missed frames at 60Hz

    private val running = AtomicBoolean(false)

    fun startIfEnabled() {
        if (!PerfTrace.isEnabled) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) return
        if (!running.compareAndSet(false, true)) return
        val mainLooper = Looper.getMainLooper()
        if (Looper.myLooper() == mainLooper) {
            ChoreographerWatcher.postCallback()
        } else {
            android.os.Handler(mainLooper).post { ChoreographerWatcher.postCallback() }
        }
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
                        Timber.tag(TAG).i("frame.jank %dms (uptime %d)", gap, SystemClock.uptimeMillis())
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
}
