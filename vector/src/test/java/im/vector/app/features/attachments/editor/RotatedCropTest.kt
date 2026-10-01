/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor

import android.graphics.RectF
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RotatedCropTest {

    private val whole = RectF(-100f, -50f, 100f, 50f)

    @Test
    fun `an upright picture holds exactly its own rectangle`() {
        val crop = RotatedCrop(200f, 100f, 0f)

        crop.fits(whole) shouldBeEqualTo true
        crop.fits(RectF(-101f, -50f, 100f, 50f)) shouldBeEqualTo false
    }

    @Test
    fun `a tilt swings the corners out, so the whole frame no longer fits`() {
        RotatedCrop(200f, 100f, 10f).fits(whole) shouldBeEqualTo false
    }

    @Test
    fun `a crop can move into a corner the tilt swung out`() {
        // Past the unturned frame's right edge, but on the picture once it is turned a quarter.
        val crop = RotatedCrop(200f, 100f, 90f)
        crop.fits(RectF(-40f, 60f, 40f, 90f)) shouldBeEqualTo true
        RotatedCrop(200f, 100f, 0f).fits(RectF(-40f, 60f, 40f, 90f)) shouldBeEqualTo false
    }

    @Test
    fun `a crop that fits needs no growth, and one that does not needs just enough`() {
        RotatedCrop(200f, 100f, 0f).requiredScale(whole) shouldBeEqualTo 1f

        val tilted = RotatedCrop(200f, 100f, 20f)
        val scale = tilted.requiredScale(whole)
        (scale > 1f) shouldBeEqualTo true
        RotatedCrop(200f * scale, 100f * scale, 20f).fits(whole) shouldBeEqualTo true
        RotatedCrop(200f * scale * 0.98f, 100f * scale * 0.98f, 20f).fits(whole) shouldBeEqualTo false
    }

    @Test
    fun `reaching stops at the picture's edge`() {
        val crop = RotatedCrop(200f, 100f, 0f)
        val reached = crop.reachable(RectF(-10f, -10f, 10f, 10f), RectF(190f, -10f, 210f, 10f))

        assertEquals(100f, reached.right, 0.5f)
    }

    @Test
    fun `frame normalisation round trips at any quarter turn`() {
        val rect = RectF(-30f, -20f, 40f, 10f)
        for (quarter in listOf(0, 90, 180, 270)) {
            val normalised = RotatedCrop.toFrameNormalised(rect, 200f, 100f, quarter)
            val back = RotatedCrop.fromFrameNormalised(normalised, 200f, 100f, quarter)
            assertEquals(rect.left, back.left, 0.001f)
            assertEquals(rect.bottom, back.bottom, 0.001f)
        }
    }

    @Test
    fun `a quarter turn clockwise turns the region with the picture`() {
        // The picture's top edge ends up on the right.
        RotatedCrop.turnedClockwise(RectF(-100f, -50f, 100f, -40f)) shouldBeEqualTo RectF(40f, -100f, 50f, 100f)
    }
}
