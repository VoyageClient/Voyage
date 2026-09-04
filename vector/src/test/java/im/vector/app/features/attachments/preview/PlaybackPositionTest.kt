/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class PlaybackPositionTest {

    private fun smooth(rawMs: Int, lastMs: Int, durationMs: Int, playing: Boolean = true) =
            PlaybackPosition.smooth(rawMs, lastMs, durationMs, playing)

    @Test
    fun `a position moving forward is reported as it is`() {
        smooth(rawMs = 600, lastMs = 500, durationMs = 20_000) shouldBeEqualTo 600
    }

    @Test
    fun `a small regression holds the last position`() {
        smooth(rawMs = 480, lastMs = 500, durationMs = 20_000) shouldBeEqualTo 500
    }

    @Test
    fun `a paused player reports its real position, however far back`() {
        smooth(rawMs = 0, lastMs = 500, durationMs = 20_000, playing = false) shouldBeEqualTo 0
    }

    @Test
    fun `a loop restart reports the start`() {
        smooth(rawMs = 90, lastMs = 19_900, durationMs = 20_000) shouldBeEqualTo 0
    }

    @Test
    fun `a short clip's loop restart is not held for the next pass`() {
        smooth(rawMs = 90, lastMs = 1_300, durationMs = 1_400) shouldBeEqualTo 0
    }

    @Test
    fun `a very short clip keeps climbing towards its end`() {
        // Every tick of a 400ms clip is close to both ends; none of them is a restart.
        smooth(rawMs = 300, lastMs = 200, durationMs = 400) shouldBeEqualTo 300
        smooth(rawMs = 400, lastMs = 300, durationMs = 400) shouldBeEqualTo 400
    }

    @Test
    fun `a very short clip's loop restart reports the start`() {
        smooth(rawMs = 20, lastMs = 300, durationMs = 400) shouldBeEqualTo 0
    }

    @Test
    fun `an unknown duration still holds a small regression`() {
        smooth(rawMs = 480, lastMs = 500, durationMs = 0) shouldBeEqualTo 500
        smooth(rawMs = 100, lastMs = 5_000, durationMs = 0) shouldBeEqualTo 100
    }
}
