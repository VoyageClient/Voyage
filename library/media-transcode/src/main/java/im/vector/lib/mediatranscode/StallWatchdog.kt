/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode

import android.os.SystemClock

/** A wedged codec must surface as a failure, never as a hang. */
internal class StallWatchdog(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    private var lastProgressAt = SystemClock.elapsedRealtime()

    fun poke() {
        lastProgressAt = SystemClock.elapsedRealtime()
    }

    fun isStalled() = SystemClock.elapsedRealtime() - lastProgressAt > timeoutMs

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}
