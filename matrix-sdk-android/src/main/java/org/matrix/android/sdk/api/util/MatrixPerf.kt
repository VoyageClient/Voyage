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
object MatrixPerf {

    private const val TAG = "VectorPerf"
    private const val LOG_THRESHOLD_MS = 5L

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
        if (!isEnabled) return
        report(name(), elapsedMillis() - startMs)
    }

    @PublishedApi
    internal fun report(name: String, elapsedMs: Long) {
        if (elapsedMs >= LOG_THRESHOLD_MS) {
            Log.i(TAG, "$name ${elapsedMs}ms")
        }
    }

    /** Log unconditionally (no threshold) — for counters/occurrence events rather than durations. */
    fun note(message: () -> String) {
        if (!isEnabled) return
        Log.i(TAG, message())
    }
}
