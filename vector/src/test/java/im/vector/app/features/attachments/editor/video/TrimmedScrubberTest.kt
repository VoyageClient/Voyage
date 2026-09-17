/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

private const val TEN_SECONDS_US = 10_000_000L

class TrimmedScrubberTest {

    @Test
    fun `an untrimmed clip scrubs over its whole length`() {
        TrimmedScrubber.maxMs(startUs = 0, endUs = TEN_SECONDS_US, durationUs = TEN_SECONDS_US) shouldBeEqualTo 10_000
    }

    @Test
    fun `a trimmed clip scrubs over what is kept`() {
        TrimmedScrubber.maxMs(startUs = 2_000_000, endUs = 6_000_000, durationUs = TEN_SECONDS_US) shouldBeEqualTo 4_000
    }

    /** The far end of the scrubber is the cut, not the end of the original. */
    @Test
    fun `the end of the scrubber is the end of the kept range`() {
        val maxMs = TrimmedScrubber.maxMs(startUs = 2_000_000, endUs = 6_000_000, durationUs = TEN_SECONDS_US)

        TrimmedScrubber.positionUs(startUs = 2_000_000, progressMs = maxMs) shouldBeEqualTo 6_000_000
    }

    @Test
    fun `the start of the scrubber is the first kept frame`() {
        TrimmedScrubber.positionUs(startUs = 2_000_000, progressMs = 0) shouldBeEqualTo 2_000_000
    }

    @Test
    fun `a playhead maps back onto the scrubber`() {
        TrimmedScrubber.progressMs(positionUs = 3_500_000, startUs = 2_000_000) shouldBeEqualTo 1_500
    }

    /** Playback can report a position just before the trim start while seeking into it. */
    @Test
    fun `a playhead before the kept range sits at zero`() {
        TrimmedScrubber.progressMs(positionUs = 1_000_000, startUs = 2_000_000) shouldBeEqualTo 0
    }

    /** endUs is zero until the duration probe lands. */
    @Test
    fun `an unknown end falls back to the duration`() {
        TrimmedScrubber.rangeUs(startUs = 0, endUs = 0, durationUs = TEN_SECONDS_US) shouldBeEqualTo TEN_SECONDS_US
    }

    /**
     * The player reports milliseconds, the cut is microseconds: a playhead parked on the cut reads as a
     * fraction short of it, and treating that as "not finished" resumed playback for a single tick.
     */
    @Test
    fun `a playhead on a cut between milliseconds counts as finished`() {
        TrimmedScrubber.isAtCut(positionUs = 94_429_000, endUs = 94_429_240, toleranceUs = 100_000) shouldBeEqualTo true
    }

    @Test
    fun `a playhead with time left to play is not finished`() {
        TrimmedScrubber.isAtCut(positionUs = 90_000_000, endUs = 94_429_240, toleranceUs = 100_000) shouldBeEqualTo false
    }

    @Test
    fun `an untrimmed clip has no cut to be at`() {
        TrimmedScrubber.isAtCut(positionUs = 94_429_000, endUs = 0, toleranceUs = 100_000) shouldBeEqualTo false
    }

    @Test
    fun `the scrubber stays draggable for a range shorter than a millisecond`() {
        TrimmedScrubber.maxMs(startUs = 0, endUs = 500, durationUs = TEN_SECONDS_US) shouldBeEqualTo 1
    }
}
