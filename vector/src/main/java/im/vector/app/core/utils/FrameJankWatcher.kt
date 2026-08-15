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
import java.util.concurrent.atomic.AtomicLong

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
        StallSampler.start()
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

    /**
     * A jank line says a frame was late; this says what the main thread was doing at the time. The
     * looper is asked to announce each message it dispatches, and a sampler thread grabs the main
     * thread's stack whenever one of them overruns — which is the only way to name work that has no
     * instrumentation of its own (a text measure, an inflate, a drawable-state storm).
     */
    private object StallSampler {

        private val startedAt = AtomicLong(0L)
        private val sampling = AtomicBoolean(false)

        fun start() {
            if (!sampling.compareAndSet(false, true)) return
            // Announcing every message costs a string per message, which is why this only runs
            // while perf logging is on.
            Looper.getMainLooper().setMessageLogging { line ->
                startedAt.set(if (line.startsWith(DISPATCH_PREFIX)) SystemClock.uptimeMillis() else 0L)
            }
            Thread(::sample, "perf-stall-sampler").apply { isDaemon = true }.start()
        }

        private fun sample() {
            val main = Looper.getMainLooper().thread
            try {
                while (PerfTrace.isEnabled) {
                    Thread.sleep(SAMPLE_INTERVAL_MS)
                    val began = startedAt.get()
                    val elapsed = SystemClock.uptimeMillis() - began
                    if (began == 0L || elapsed < STALL_THRESHOLD_MS) continue
                    Log.i(TAG, "main.stall ${elapsed}ms at ${describe(main.stackTrace)}")
                    // One report per stall, rather than one per sample while it drags on.
                    Thread.sleep(REPORT_INTERVAL_MS)
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                Looper.getMainLooper().setMessageLogging(null)
                sampling.set(false)
            }
        }

        /** Ours first, since that is what can be changed; the platform frames when none are ours. */
        private fun describe(stack: Array<StackTraceElement>): String {
            val ours = stack.filter { it.className.startsWith("im.vector") || it.className.startsWith("org.matrix") }
            return (ours.takeIf { it.isNotEmpty() } ?: stack.toList())
                    .take(FRAMES_LOGGED)
                    .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
        }

        private const val DISPATCH_PREFIX = ">>>>> Dispatching"
        private const val SAMPLE_INTERVAL_MS = 30L
        private const val STALL_THRESHOLD_MS = 100L
        private const val REPORT_INTERVAL_MS = 150L
        private const val FRAMES_LOGGED = 10
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
