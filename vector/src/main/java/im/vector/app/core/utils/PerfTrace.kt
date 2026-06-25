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
     * Master switch. Set from the settings toggle at app startup and on preference change.
     * Off by default — when off, [time] / [timeSuspending] / [mark] all short-circuit so
     * instrumentation is essentially free in release-style usage.
     *
     * Volatile so flips from the settings thread are immediately visible to other threads.
     */
    @Volatile
    @JvmField
    var isEnabled: Boolean = false

    private val NOOP_MARKER = Marker("", 0L, sample = false)

    /**
     * Measure a synchronous block. Returns the block's result so it can be used inline.
     */
    inline fun <T> time(name: String, block: () -> T): T {
        if (!isEnabled) return block()
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
        if (!isEnabled) return block()
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
    fun mark(name: String): Marker {
        if (!isEnabled) return NOOP_MARKER
        beginSection(name)
        return Marker(name, SystemClock.elapsedRealtime(), sample = true)
    }

    class Marker internal constructor(
            private val name: String,
            private val startMs: Long,
            private val sample: Boolean,
    ) {
        fun end() {
            if (!sample) return
            val elapsed = SystemClock.elapsedRealtime() - startMs
            endSection(elapsed, name)
        }
    }

    /**
     * Log a single already-measured duration under the [TAG], for spans that don't fit the begin/end
     * pattern (e.g. a frame interval observed in a scroll callback). No-op when disabled.
     */
    fun report(name: String, elapsedMs: Long) {
        if (!isEnabled) return
        Timber.tag(TAG).i("%s %dms", name, elapsedMs)
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
