/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

/**
 * Maps the playback scrubber onto the part of a clip that is kept.
 *
 * Tied to the original duration instead, its far end sat past the trim, where playback treats the
 * position as finished and starts the clip over.
 */
object TrimmedScrubber {

    /** Length of what is kept; [endUs] is zero until the duration is known. */
    fun rangeUs(startUs: Long, endUs: Long, durationUs: Long): Long =
            ((if (endUs > 0) endUs else durationUs) - startUs).coerceAtLeast(0)

    /** A seek bar's max, in milliseconds, never zero so the bar stays draggable. */
    fun maxMs(startUs: Long, endUs: Long, durationUs: Long): Int =
            (rangeUs(startUs, endUs, durationUs) / 1000).toInt().coerceAtLeast(1)

    /** Where a scrubber at [progressMs] sits in the clip. */
    fun positionUs(startUs: Long, progressMs: Int): Long = startUs + progressMs * 1000L

    /**
     * Whether [positionUs] has nothing left to play before the cut at [endUs], allowing [toleranceUs]:
     * a player reporting whole milliseconds can never land exactly on a microsecond-precision cut, and
     * resuming a sliver before it only plays a single tick.
     */
    fun isAtCut(positionUs: Long, endUs: Long, toleranceUs: Long): Boolean =
            endUs > 0 && positionUs >= endUs - toleranceUs

    /** Where a playhead at [positionUs] sits on the scrubber. */
    fun progressMs(positionUs: Long, startUs: Long): Int = ((positionUs - startUs).coerceAtLeast(0) / 1000).toInt()
}
