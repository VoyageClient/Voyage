/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import org.matrix.android.sdk.internal.session.SessionScope
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Per-room monotonic redaction counter. Pruning rewrites the `event` table underneath the timeline's
 * cached static-chunk mappings; the prune also touches the target's `timeline_event` row to re-fire
 * the chunk flow, and a moved stamp tells the collecting [SqlTimeline] to drop those cached mappings
 * before rebuilding.
 */
@SessionScope
internal class TimelineRedactionSignal @Inject constructor() {

    private val counters = ConcurrentHashMap<String, Long>()

    fun onRedaction(roomId: String) {
        counters.merge(roomId, 1L, Long::plus)
    }

    fun stamp(roomId: String): Long = counters[roomId] ?: 0L
}
