/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterThan
import org.junit.Test

class SpeedTimeMapTest {

    @Test
    fun `a clip left at normal speed still starts at zero`() {
        val map = SpeedTimeMap(sourceStartUs = 2_000_000, rate = 1f)

        map.outputUsFor(2_000_000) shouldBeEqualTo 0
        map.outputUsFor(5_000_000) shouldBeEqualTo 3_000_000
    }

    @Test
    fun `doubling the speed halves the output time`() {
        val map = SpeedTimeMap(sourceStartUs = 0, rate = 2f)

        map.outputUsFor(0) shouldBeEqualTo 0
        map.outputUsFor(10_000_000) shouldBeEqualTo 5_000_000
    }

    @Test
    fun `halving the speed doubles it`() {
        val map = SpeedTimeMap(sourceStartUs = 0, rate = 0.5f)

        map.outputUsFor(10_000_000) shouldBeEqualTo 20_000_000
    }

    @Test
    fun `time never runs backwards`() {
        val map = SpeedTimeMap(sourceStartUs = 1_000_000, rate = 1.5f)

        var previous = -1L
        for (us in 1_000_000..9_000_000 step 100_000) {
            val output = map.outputUsFor(us.toLong())
            output shouldBeGreaterThan previous
            previous = output
        }
    }

    @Test
    fun `a speed the slider could not quite land on 1 does not count as re-timed`() {
        // The slider has ten thousand steps, so "100%" is rarely exactly 1. Treating those as a
        // re-time would cost a clip nobody touched its lossless remux and its original audio.
        SpeedTimeMap.retimes(1f) shouldBeEqualTo false
        SpeedTimeMap.retimes(0.99999994f) shouldBeEqualTo false
        SpeedTimeMap.retimes(1.0005f) shouldBeEqualTo false
    }

    @Test
    fun `a speed anyone could have meant does count as re-timed`() {
        SpeedTimeMap.retimes(1.01f) shouldBeEqualTo true
        SpeedTimeMap.retimes(0.99f) shouldBeEqualTo true
        SpeedTimeMap.retimes(3f) shouldBeEqualTo true
        SpeedTimeMap.retimes(0.1f) shouldBeEqualTo true
    }

    @Test
    fun `a rate of zero cannot stretch the clip to infinity`() {
        val map = SpeedTimeMap(sourceStartUs = 0, rate = 0f)

        // Clamped rather than dividing by zero, which would put every frame at the same timestamp.
        (map.outputUsFor(1_000_000) < Long.MAX_VALUE) shouldBeEqualTo true
        map.rate shouldBeGreaterThan 0f
    }
}
