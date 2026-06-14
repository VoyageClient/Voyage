/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks which hidden media events the user has chosen to reveal for the current session.
 * Reveals intentionally do not persist across restarts, matching Element Web/X behaviour.
 */
@Singleton
class MediaContentRevealManager @Inject constructor() {

    private val revealedEventIds = Collections.synchronizedSet(mutableSetOf<String>())

    fun isRevealed(eventId: String): Boolean = revealedEventIds.contains(eventId)

    fun reveal(eventId: String) {
        revealedEventIds.add(eventId)
    }
}
