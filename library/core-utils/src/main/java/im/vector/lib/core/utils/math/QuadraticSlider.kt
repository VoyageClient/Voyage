/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.core.utils.math

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Maps a value onto a seek bar so that the further the thumb is from [centre], the faster the value
 * moves — fine control in the middle, coarse at the extremes. Ported from NewPipe's
 * `SliderStrategy.Quadratic`, which its playback speed dialog uses for exactly this.
 *
 * @param maximumProgress the seek bar's range. Large keeps rounding error away from the value.
 */
class QuadraticSlider(
        private val minimum: Float,
        private val maximum: Float,
        private val centre: Float,
        val maximumProgress: Int,
) {

    init {
        require(centre in minimum..maximum) { "The centre must lie between the minimum and the maximum" }
    }

    private val leftGap = minimum - centre
    private val rightGap = maximum - centre
    private val centreProgress = maximumProgress / 2

    fun progressOf(value: Float): Int {
        val difference = value.coerceIn(minimum, maximum) - centre
        val root = if (difference >= 0) sqrt(difference / rightGap) else -sqrt(abs(difference / leftGap))
        return (centreProgress + (root * centreProgress).roundToInt()).coerceIn(0, maximumProgress)
    }

    fun valueOf(progress: Int): Float {
        val offset = progress.coerceIn(0, maximumProgress) - centreProgress
        val square = (offset.toFloat() / centreProgress).toDouble().pow(2.0).toFloat()
        return (square * (if (offset >= 0) rightGap else leftGap) + centre).coerceIn(minimum, maximum)
    }
}
