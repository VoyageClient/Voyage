/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

/** Keeps the fade deadline across timeline rebinds. */
class ReadMarkerFadeTracker(private val totalDurationMs: Long) {

    @Volatile private var sessionKey: String? = null

    @Volatile private var endElapsedMs: Long? = null

    @Volatile var isFadedOut: Boolean = false
        private set

    fun onSession(firstUnreadEventId: String?): String? {
        // Read receipts can clear unread state before the visible separator finishes fading.
        if (firstUnreadEventId == null && endElapsedMs != null && !isFadedOut) {
            return sessionKey
        }
        if (firstUnreadEventId != sessionKey) {
            sessionKey = firstUnreadEventId
            endElapsedMs = null
            isFadedOut = false
        }
        return sessionKey
    }

    fun onSeen(elapsedMs: Long) {
        if (endElapsedMs == null) {
            endElapsedMs = elapsedMs + totalDurationMs
        }
    }

    /** Milliseconds of animation left to play, null until the marker has been seen. */
    fun remainingMs(elapsedMs: Long): Long? {
        val end = endElapsedMs ?: return null
        return (end - elapsedMs).coerceIn(0L, totalDurationMs)
    }

    fun markFadedOut() {
        isFadedOut = true
    }
}
