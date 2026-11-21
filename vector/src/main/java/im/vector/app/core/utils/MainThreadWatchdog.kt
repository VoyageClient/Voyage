/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.utils

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import org.matrix.android.sdk.api.debug.DebugLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal class MainThreadStallTracker(private val thresholdMs: Long = 5000L) {
    enum class Action { POST, STALLED, NONE }
    private var pendingSince: Long? = null
    private var reported = false

    @Synchronized
    fun poll(now: Long, active: Boolean): Action {
        if (!active) {
            reset()
            return Action.NONE
        }
        val since = pendingSince
        if (since == null) {
            pendingSince = now
            return Action.POST
        }
        if (!reported && now - since >= thresholdMs) {
            reported = true
            return Action.STALLED
        }
        return Action.NONE
    }

    @Synchronized
    fun acknowledge(now: Long): Long? {
        val duration = pendingSince?.takeIf { reported }?.let { now - it }
        reset()
        return duration
    }

    @Synchronized
    fun reset() {
        pendingSince = null
        reported = false
    }
}

internal class MainThreadWatchdog : DefaultLifecycleObserver {
    private val main = Handler(Looper.getMainLooper())
    private val tracker = MainThreadStallTracker()
    private var enabled = false
    private var generation = 0L
    private var scheduler: ScheduledExecutorService? = null
    private var heartbeat: Runnable? = null

    fun setEnabled(value: Boolean) {
        main.post {
            if (value == enabled) return@post
            enabled = value
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            if (value) lifecycle.addObserver(this) else {
                lifecycle.removeObserver(this)
                stop()
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        synchronized(this) {
            if (!enabled || scheduler != null) return
            tracker.reset()
            val token = ++generation
            val pulse = Runnable {
                val duration = synchronized(this) {
                    if (!enabled || generation != token) return@Runnable
                    tracker.acknowledge(SystemClock.uptimeMillis())
                }
                if (duration != null) DebugLog.w { "APPDBG main-thread-recovered durationMs=$duration" }
            }
            heartbeat = pulse
            scheduler = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "main-thread-watchdog").apply { isDaemon = true } }.also {
                it.scheduleWithFixedDelay({ tick(token, pulse) }, 0, 1, TimeUnit.SECONDS)
            }
        }
    }

    private fun tick(token: Long, pulse: Runnable) {
        val action = synchronized(this) {
            if (!enabled || token != generation) return
            tracker.poll(SystemClock.uptimeMillis(), DebugLog.enabled && !Debug.isDebuggerConnected()).also {
                if (it == MainThreadStallTracker.Action.POST) main.post(pulse)
            }
        }
        when (action) {
            MainThreadStallTracker.Action.POST -> Unit
            MainThreadStallTracker.Action.STALLED -> DebugLog.w {
                "APPDBG main-thread-stalled thresholdMs=5000 stack=" +
                        Looper.getMainLooper().thread.stackTrace.take(40).joinToString(" > ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
            }
            MainThreadStallTracker.Action.NONE -> Unit
        }
    }

    override fun onStop(owner: LifecycleOwner) = stop()

    @Synchronized
    private fun stop() {
        generation++
        heartbeat?.let { main.removeCallbacks(it) }
        heartbeat = null
        scheduler?.shutdownNow()
        scheduler = null
        tracker.reset()
    }
}
