/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class RotationControlTest {

    @Test
    fun `without snapping any angle is kept, normalised to a single turn`() {
        AngleSnap.snap(2f, enabled = false) shouldBeEqualTo 2f
        AngleSnap.snap(200f, enabled = false) shouldBeEqualTo 200f
        AngleSnap.snap(-10f, enabled = false) shouldBeEqualTo 350f
        AngleSnap.snap(370f, enabled = false) shouldBeEqualTo 10f
    }

    @Test
    fun `snapping pulls a nearby angle onto a 45 degree multiple`() {
        AngleSnap.snap(2f, enabled = true) shouldBeEqualTo 0f
        AngleSnap.snap(358f, enabled = true) shouldBeEqualTo 0f
        AngleSnap.snap(137f, enabled = true) shouldBeEqualTo 135f
        AngleSnap.snap(-43f, enabled = true) shouldBeEqualTo 315f
    }

    @Test
    fun `the 90 degree steps go to the neighbouring multiple of 90`() {
        QuarterStep.next(0f) shouldBeEqualTo 90f
        QuarterStep.next(30f) shouldBeEqualTo 90f
        QuarterStep.next(270f) shouldBeEqualTo 0f
        QuarterStep.next(300f) shouldBeEqualTo 0f
        QuarterStep.previous(30f) shouldBeEqualTo 0f
        QuarterStep.previous(0f) shouldBeEqualTo 270f
        QuarterStep.previous(100f) shouldBeEqualTo 90f
    }

    @Test
    fun `snapping catches within five degrees`() {
        AngleSnap.snap(40f, enabled = true) shouldBeEqualTo 45f
        AngleSnap.snap(39f, enabled = true) shouldBeEqualTo 39f
    }

    @Test
    fun `snapping leaves angles away from a multiple free`() {
        AngleSnap.snap(12f, enabled = true) shouldBeEqualTo 12f
        AngleSnap.snap(250f, enabled = true) shouldBeEqualTo 250f
    }

    @Test
    fun `a full angle splits into quarter turns and a tilt within half a quarter`() {
        RotationSplit.of(0f) shouldBeEqualTo RotationSplit(0, 0f)
        RotationSplit.of(30f) shouldBeEqualTo RotationSplit(0, 30f)
        RotationSplit.of(50f) shouldBeEqualTo RotationSplit(90, -40f)
        RotationSplit.of(200f) shouldBeEqualTo RotationSplit(180, 20f)
        RotationSplit.of(350f) shouldBeEqualTo RotationSplit(0, -10f)
    }
}
