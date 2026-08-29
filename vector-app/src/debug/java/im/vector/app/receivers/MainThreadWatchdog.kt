/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.receivers

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Logs the main thread's stack whenever it stops servicing the looper for longer than
 * [STALL_THRESHOLD_MS], so a freeze reports where it is stuck instead of being guessed at.
 * Samples repeatedly while stalled, so a slow-but-moving main thread reads differently from a
 * hard block. Look for the ANRWD tag.
 */
object MainThreadWatchdog {

    private const val STALL_THRESHOLD_MS = 1_000L
    private const val SAMPLE_INTERVAL_MS = 500L
    private const val MAX_FRAMES = 40

    private val started = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainThread = Looper.getMainLooper().thread

    @Volatile private var beatSentAt = 0L
    @Volatile private var beatPending = false

    fun start() {
        if (!started.compareAndSet(false, true)) return
        Timber.i("ANRWD: watchdog started (threshold ${STALL_THRESHOLD_MS}ms)")
        thread(name = "anr-watchdog", isDaemon = true) { loop() }
    }

    private fun loop() {
        var stallStart = 0L
        var lastTop: String? = null
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (!beatPending) {
                if (stallStart != 0L) {
                    Timber.w("ANRWD: main thread recovered after ${now - stallStart}ms")
                    stallStart = 0L
                    lastTop = null
                }
                beatSentAt = now
                beatPending = true
                mainHandler.post { beatPending = false }
            } else {
                val waited = now - beatSentAt
                if (waited >= STALL_THRESHOLD_MS) {
                    if (stallStart == 0L) stallStart = beatSentAt
                    lastTop = dumpMainThread(waited, lastTop)
                }
            }
            Thread.sleep(SAMPLE_INTERVAL_MS)
        }
    }

    /** Full frames only when the main thread moved since the last sample; returns the new top frame. */
    private fun dumpMainThread(waitedMs: Long, lastTop: String?): String? {
        val frames = mainThread.stackTrace
        val top = frames.firstOrNull { !it.className.startsWith("android.os.") }?.toString()
                ?: frames.firstOrNull()?.toString()
        Timber.w("ANRWD: main thread stalled ${waitedMs}ms, state=${mainThread.state}, at $top")
        if (top != lastTop) {
            frames.take(MAX_FRAMES).forEach { Timber.w("ANRWD:     at $it") }
        }
        return top
    }
}
