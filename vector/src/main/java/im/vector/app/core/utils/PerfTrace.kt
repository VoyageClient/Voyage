/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.utils

import android.os.SystemClock
import android.os.Trace
import timber.log.Timber

/**
 * Lightweight performance instrumentation.
 *
 * Each measured block:
 *  - opens an [android.os.Trace] section so it shows up in Perfetto / Android Studio
 *    profiler with a readable name
 *  - logs `"<name> Xms"` via Timber with the tag `VectorPerf`, but only when the elapsed
 *    duration crosses [LOG_THRESHOLD_MS] (avoids drowning the log in single-digit ms work)
 *
 * Grep the log stream with `adb logcat -s VectorPerf` to see hot operations as they happen.
 *
 * Use [time] for ordinary synchronous work, [timeSuspending] for `suspend` blocks.
 */
object PerfTrace {

    private const val TAG = "VectorPerf"
    private const val LOG_THRESHOLD_MS = 5L

    /**
     * Measure a synchronous block. Returns the block's result so it can be used inline.
     */
    inline fun <T> time(name: String, block: () -> T): T {
        beginSection(name)
        val start = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            val elapsed = SystemClock.elapsedRealtime() - start
            endSection(elapsed, name)
        }
    }

    /**
     * Measure a `suspend` block. Same semantics as [time] but lets the lambda suspend
     * (e.g. an IO call, a `withContext` switch).
     */
    suspend inline fun <T> timeSuspending(name: String, block: () -> T): T {
        beginSection(name)
        val start = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            val elapsed = SystemClock.elapsedRealtime() - start
            endSection(elapsed, name)
        }
    }

    /**
     * Manual begin/end. Prefer [time] when possible; this exists for cases where the start
     * and end happen across different callbacks (e.g. user input → async result).
     */
    fun mark(name: String): Marker = Marker(name, SystemClock.elapsedRealtime()).also {
        beginSection(name)
    }

    class Marker internal constructor(private val name: String, private val startMs: Long) {
        fun end() {
            val elapsed = SystemClock.elapsedRealtime() - startMs
            endSection(elapsed, name)
        }
    }

    @PublishedApi
    internal fun beginSection(name: String) {
        // Trace sections must be <= 127 chars; clamp to keep us within the kernel limit.
        Trace.beginSection(if (name.length > 127) name.substring(0, 127) else name)
    }

    @PublishedApi
    internal fun endSection(elapsedMs: Long, name: String) {
        Trace.endSection()
        if (elapsedMs >= LOG_THRESHOLD_MS) {
            Timber.tag(TAG).i("%s %dms", name, elapsedMs)
        }
    }
}
