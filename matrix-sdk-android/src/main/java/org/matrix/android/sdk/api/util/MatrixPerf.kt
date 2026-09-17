/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.util

import android.util.Log

/**
 * SDK-side twin of the app's PerfTrace: logs `"<name> Xms"` under the `VectorPerf` tag when a
 * measured block crosses the threshold. The app flips [isEnabled] together with its own switch
 * (Settings → Advanced → Perf logging); when off everything short-circuits.
 *
 * Uses android.util.Log directly (not Timber) so the output reaches logcat in release builds too.
 */
// android.util.Log on purpose: release builds plant no Timber tree, and these markers are read via logcat.
@Suppress("LogNotTimber")
object MatrixPerf {

    private const val TAG = "VectorPerf"

    /** Anything faster than this is not logged; lower it to see sub-millisecond steps. */
    @Volatile
    @JvmStatic
    var logThresholdMs: Long = 5L

    @PublishedApi
    internal fun elapsedMillis(): Long = System.nanoTime() / 1_000_000

    @Volatile
    @JvmField
    var isEnabled: Boolean = false

    inline fun <T> time(name: String, block: () -> T): T {
        if (!isEnabled) return block()
        val start = elapsedMillis()
        try {
            return block()
        } finally {
            report(name, elapsedMillis() - start)
        }
    }

    suspend inline fun <T> timeSuspending(name: String, block: () -> T): T {
        if (!isEnabled) return block()
        val start = elapsedMillis()
        try {
            return block()
        } finally {
            report(name, elapsedMillis() - start)
        }
    }

    fun now(): Long = if (isEnabled) elapsedMillis() else 0L

    /** End of a [now]-based span; logs if over threshold. Pass a lazily-built name for cheap disable. */
    fun end(startMs: Long, name: () -> String) {
        // startMs == 0 means [now] ran while measurement was off, so there is no span to report — only a
        // number the size of the process uptime.
        if (!isEnabled || startMs == 0L) return
        report(name(), elapsedMillis() - startMs)
    }

    @PublishedApi
    internal fun report(name: String, elapsedMs: Long) {
        if (isAggregating) {
            val bucket = totals.getOrPut(name) { longArrayOf(0, 0) }
            synchronized(bucket) {
                bucket[0] += elapsedMs
                bucket[1]++
            }
            return
        }
        if (elapsedMs >= logThresholdMs) {
            Log.i(TAG, "$name ${elapsedMs}ms")
        }
    }

    /** Aggregate cheap calls so instrumentation does not dominate their combined cost. */
    @Volatile
    @JvmStatic
    var isAggregating: Boolean = false

    private val totals = java.util.concurrent.ConcurrentHashMap<String, LongArray>()

    fun resetTotals() = totals.clear()

    /** Slowest first, one line each: `<total>ms calls=<n> <name>`. */
    fun dumpTotals() {
        totals.entries
                .sortedByDescending { it.value[0] }
                .forEach { Log.i(TAG, "TOTAL ${it.value[0]}ms calls=${it.value[1]} ${it.key}") }
        Log.i(TAG, "TOTAL end (${totals.size} names)")
    }

    /** Log unconditionally (no threshold) — for counters/occurrence events rather than durations. */
    fun note(message: () -> String) {
        if (!isEnabled) return
        Log.i(TAG, message())
    }
}
