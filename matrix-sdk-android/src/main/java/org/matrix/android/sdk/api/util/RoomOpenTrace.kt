/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.util

import android.util.Log

/**
 * Stage log for a single room open, from the tap to the first painted messages: each stage reports the
 * step since the previous one and the total since the tap, so a stall lands on one named line.
 *
 * Shares [MatrixPerf]'s switch (Settings -> Advanced -> Perf logging); read with `adb logcat -s ROOMOPEN`.
 * Lives in the SDK so the timeline internals can post into the same span the app started.
 */
// android.util.Log on purpose: release builds plant no Timber tree, and these markers are read via logcat.
@Suppress("LogNotTimber")
object RoomOpenTrace {

    private const val TAG = "ROOMOPEN"

    // A stale span (the user backed out mid-open) would otherwise attribute the next room's stages to it.
    private const val SPAN_TTL_MS = 120_000L

    private var roomId: String? = null
    private var startMs = 0L
    private var lastMs = 0L

    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    @Synchronized
    fun begin(roomId: String, from: String) {
        if (!MatrixPerf.isEnabled) return
        this.roomId = roomId
        startMs = nowMs()
        lastMs = startMs
        Log.i(TAG, "$roomId begin from=$from")
    }

    /** For entry points the navigator doesn't go through (shortcut, notification, cold start). */
    @Synchronized
    fun beginIfDifferent(roomId: String, from: String) {
        if (this.roomId != roomId) begin(roomId, from)
    }

    @Synchronized
    fun stage(name: String, detail: String? = null) {
        val room = roomId ?: return
        val now = nowMs()
        if (now - startMs > SPAN_TTL_MS) {
            roomId = null
            return
        }
        val step = now - lastMs
        lastMs = now
        Log.i(TAG, "$room $name +${step}ms total=${now - startMs}ms${detail?.let { " $it" } ?: ""}")
    }

    /** Same as [stage] but only for the room that owns the current span (SDK callers serve every room). */
    @Synchronized
    fun stageFor(roomId: String, name: String, detail: String? = null) {
        if (this.roomId != roomId) return
        stage(name, detail)
    }

    @Synchronized
    fun end(name: String, detail: String? = null) {
        if (roomId == null) return
        stage(name, detail)
        roomId = null
    }
}
