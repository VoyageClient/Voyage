/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.core.utils.math

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterThan
import org.junit.Test
import kotlin.math.abs

class QuadraticSliderTest {

    private val sut = QuadraticSlider(minimum = 0.1f, maximum = 3f, centre = 1f, maximumProgress = 10_000)

    @Test
    fun `the ends and the centre land where they should`() {
        sut.progressOf(0.1f) shouldBeEqualTo 0
        sut.progressOf(1f) shouldBeEqualTo 5_000
        sut.progressOf(3f) shouldBeEqualTo 10_000
    }

    @Test
    fun `a value survives the round trip through the slider`() {
        listOf(0.1f, 0.25f, 0.5f, 0.9f, 1f, 1.5f, 2f, 3f).forEach { value ->
            (abs(sut.valueOf(sut.progressOf(value)) - value) < 0.01f) shouldBeEqualTo true
        }
    }

    @Test
    fun `nothing leaves the bar or the value range, whatever it is handed`() {
        sut.progressOf(-5f) shouldBeEqualTo 0
        sut.progressOf(100f) shouldBeEqualTo 10_000
        // Rebuilt by arithmetic rather than returned verbatim, so compare within an ulp or two.
        (abs(sut.valueOf(-100) - 0.1f) < 0.001f) shouldBeEqualTo true
        (abs(sut.valueOf(99_999) - 3f) < 0.001f) shouldBeEqualTo true
    }

    @Test
    fun `the scale is finer near the centre than at the extremes`() {
        // The point of the quadratic mapping: the same step of the bar moves the value less in the middle.
        val nearCentre = abs(sut.valueOf(5_100) - sut.valueOf(5_000))
        val nearEnd = abs(sut.valueOf(10_000) - sut.valueOf(9_900))
        nearEnd shouldBeGreaterThan nearCentre
    }

    @Test
    fun `the centre need not be the average of the two ends`() {
        val offCentre = QuadraticSlider(minimum = 0f, maximum = 100f, centre = 10f, maximumProgress = 1_000)
        offCentre.progressOf(10f) shouldBeEqualTo 500
        (abs(offCentre.valueOf(offCentre.progressOf(75f)) - 75f) < 0.5f) shouldBeEqualTo true
    }
}
