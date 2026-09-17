/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

private const val INTERVAL_MS = 100L
private const val MAX_STEP_US = 1_200_000L

class PlayheadGlideTest {

    private fun glides(fromUs: Long, toUs: Long, speed: Float = 1f) =
            PlayheadGlide.glides(fromUs, toUs, INTERVAL_MS, speed, MAX_STEP_US)

    @Test
    fun `one report of playback glides`() {
        glides(fromUs = 1_000_000, toUs = 1_100_000) shouldBeEqualTo true
    }

    @Test
    fun `a position wobbling slightly backwards still glides`() {
        glides(fromUs = 1_000_000, toUs = 990_000) shouldBeEqualTo true
    }

    /**
     * The playhead sits a report ahead of playback while gliding, so a stalled sink reports that much
     * behind it — three times as much at 3x, which used to snap and replay the same sliver of clip.
     */
    @Test
    fun `a stall at speed glides rather than snapping back`() {
        glides(fromUs = 1_300_000, toUs = 1_000_000, speed = 3f) shouldBeEqualTo true
    }

    @Test
    fun `a seek backwards snaps`() {
        glides(fromUs = 5_000_000, toUs = 1_000_000) shouldBeEqualTo false
    }

    @Test
    fun `a jump forwards snaps`() {
        glides(fromUs = 1_000_000, toUs = 4_000_000) shouldBeEqualTo false
    }

    /** At 8x a report is 800ms of clip, which is playback advancing rather than a jump. */
    @Test
    fun `a report at high speed is not mistaken for a jump`() {
        glides(fromUs = 1_000_000, toUs = 1_800_000, speed = 8f) shouldBeEqualTo true
    }

    @Test
    fun `the aim is one report of clip time ahead`() {
        PlayheadGlide.aheadUs(INTERVAL_MS, speed = 3f) shouldBeEqualTo 300_000
    }

    @Test
    fun `the aim never passes the cut`() {
        PlayheadGlide.aimUs(toUs = 4_950_000, limitUs = 5_000_000, intervalMs = INTERVAL_MS, speed = 1f) shouldBeEqualTo 5_000_000
    }

    /** endUs is zero until the metadata probe lands, and is no limit then. */
    @Test
    fun `an unknown cut does not limit the aim`() {
        PlayheadGlide.aimUs(toUs = 4_950_000, limitUs = 0, intervalMs = INTERVAL_MS, speed = 1f) shouldBeEqualTo 5_050_000
    }

    /** A stalled sink repeats a position; aiming at it again would cover the same clip over and over. */
    @Test
    fun `a repeated report holds the playhead where it is`() {
        PlayheadGlide.aimUs(
                toUs = 1_000_000,
                limitUs = 0,
                intervalMs = INTERVAL_MS,
                speed = 1f,
                notBeforeUs = 1_300_000,
        ) shouldBeEqualTo 1_300_000
    }

    @Test
    fun `the cut still wins over the playhead`() {
        PlayheadGlide.aimUs(
                toUs = 4_990_000,
                limitUs = 5_000_000,
                intervalMs = INTERVAL_MS,
                speed = 1f,
                notBeforeUs = 5_200_000,
        ) shouldBeEqualTo 5_000_000
    }
}
