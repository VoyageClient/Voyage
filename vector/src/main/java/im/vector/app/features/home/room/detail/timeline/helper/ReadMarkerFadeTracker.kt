/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

/**
 * Deadline for the "Unread messages" separator to disappear, reset by each new batch of unread messages.
 * It lives here rather than in the view holder because the timeline rebinds its items constantly, which
 * would keep restarting the animation.
 */
class ReadMarkerFadeTracker(private val totalDurationMs: Long) {

    @Volatile private var sessionKey: String? = null

    @Volatile private var endElapsedMs: Long? = null

    @Volatile var isFadedOut: Boolean = false
        private set

    /** @return the key of the current unread session. */
    fun onSession(firstUnreadEventId: String?): String? {
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
