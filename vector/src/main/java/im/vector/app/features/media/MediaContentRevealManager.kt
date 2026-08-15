/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    private val _revealedEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Emits an event id whenever it's revealed, so other surfaces (e.g. the composer reply preview)
     *  can update without waiting for a rebind. */
    val revealedEvents: SharedFlow<String> = _revealedEvents.asSharedFlow()

    fun isRevealed(eventId: String): Boolean = revealedEventIds.contains(eventId)

    fun reveal(eventId: String) {
        if (revealedEventIds.add(eventId)) {
            _revealedEvents.tryEmit(eventId)
        }
    }

    /** Reveal decisions are per-account; called when the active account switches. */
    fun clearAll() {
        revealedEventIds.clear()
    }
}
