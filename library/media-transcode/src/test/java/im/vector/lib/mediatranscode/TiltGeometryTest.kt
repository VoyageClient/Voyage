/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import kotlin.math.abs

class TiltGeometryTest {

    @Test
    fun `no tilt leaves points alone`() {
        TiltGeometry.untilt(0.2f, 0.7f, 0f, 1920f, 1080f).toList() shouldBeEqualTo listOf(0.2f, 0.7f)
    }

    @Test
    fun `the centre stays put`() {
        val centre = TiltGeometry.untilt(0.5f, 0.5f, 30f, 640f, 480f)
        centre[0].shouldBeClose(0.5f)
        centre[1].shouldBeClose(0.5f)
    }

    @Test
    fun `a quarter turn of a square maps the top left corner onto the bottom left`() {
        // The picture turned clockwise shows its bottom-left corner at the frame's top-left.
        val corner = TiltGeometry.untilt(0f, 0f, 90f, 100f, 100f)
        corner[0].shouldBeClose(0f)
        corner[1].shouldBeClose(1f)
    }

    @Test
    fun `distances are kept, since nothing is scaled`() {
        val a = TiltGeometry.untilt(0.4f, 0.5f, 20f, 1000f, 1000f)
        val b = TiltGeometry.untilt(0.6f, 0.5f, 20f, 1000f, 1000f)
        val distance = kotlin.math.hypot((a[0] - b[0]) * 1000f, (a[1] - b[1]) * 1000f)
        distance.shouldBeClose(200f, tolerance = 0.05f)
    }

    private fun Float.shouldBeClose(expected: Float, tolerance: Float = TOLERANCE) {
        (abs(this - expected) < tolerance) shouldBeEqualTo true
    }

    companion object {
        private const val TOLERANCE = 0.0005f
    }
}
