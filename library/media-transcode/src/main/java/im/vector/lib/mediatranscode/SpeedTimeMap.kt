/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode

import kotlin.math.abs

/**
 * Where each moment of the source lands in the output. Both tracks share one map: they have to
 * agree to the microsecond, or the picture drifts away from the sound over the length of the clip.
 */
internal class SpeedTimeMap(private val sourceStartUs: Long, rate: Float) {

    val rate = rate.coerceAtLeast(MIN_RATE)

    fun outputUsFor(sourceUs: Long): Long = ((sourceUs - sourceStartUs) / rate).toLong()

    companion object {
        /** Below this the output would be longer than any plausible upload, and 0 would divide by zero. */
        private const val MIN_RATE = 0.01f

        private const val TOLERANCE = 0.001f

        /**
         * Whether [speed] is far enough from 1 to be worth re-timing for. Compared with a tolerance
         * because the speed comes off a ten-thousand-step slider: an exact test would send a clip
         * nobody re-timed down the re-encoding path over a rounding error, costing it a lossless
         * remux and its original audio.
         */
        fun retimes(speed: Float) = abs(speed - 1f) >= TOLERANCE
    }
}
