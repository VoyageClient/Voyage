/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor

import android.graphics.RectF
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CropRotationAnchorTest {

    private val anchor = CropRotationAnchor()
    private val crop = RectF(0.1f, 0.2f, 0.6f, 0.7f)

    // Mirroring a normalised edge cannot land on the exact decimal it started from.
    private fun RectF?.shouldBe(left: Float, top: Float, right: Float, bottom: Float) {
        val rect = requireNotNull(this)
        assertEquals(left, rect.left, TOLERANCE)
        assertEquals(top, rect.top, TOLERANCE)
        assertEquals(right, rect.right, TOLERANCE)
        assertEquals(bottom, rect.bottom, TOLERANCE)
    }

    @Test
    fun `with nothing anchored the refitted crop stands`() {
        anchor.cropFor(90).shouldBeNull()
    }

    @Test
    fun `back at the orientation it was set in, the crop is handed back whole`() {
        anchor.anchor(crop, rotationDegrees = 0)

        anchor.cropFor(0) shouldBeEqualTo crop
    }

    /** Half a turn leaves the frame the same shape, so the crop fits — mirrored into its new corner. */
    @Test
    fun `half a turn keeps the crop's size`() {
        anchor.anchor(crop, rotationDegrees = 0)

        anchor.cropFor(180).shouldBe(0.4f, 0.3f, 0.9f, 0.8f)
    }

    @Test
    fun `sideways there is nothing to hand back`() {
        anchor.anchor(crop, rotationDegrees = 0)

        anchor.cropFor(90).shouldBeNull()
        anchor.cropFor(270).shouldBeNull()
    }

    /** Four quarter-turns used to shrink the crop twice; a full turn must return it untouched. */
    @Test
    fun `a full turn returns the crop untouched`() {
        anchor.anchor(crop, rotationDegrees = 0)

        anchor.cropFor(360) shouldBeEqualTo crop
    }

    @Test
    fun `a crop set while sideways is what that orientation returns to`() {
        anchor.anchor(crop, rotationDegrees = 90)

        anchor.cropFor(90) shouldBeEqualTo crop
        anchor.cropFor(270).shouldBe(0.4f, 0.3f, 0.9f, 0.8f)
        anchor.cropFor(0).shouldBeNull()
    }

    @Test
    fun `a reset leaves nothing to return to`() {
        anchor.anchor(crop, rotationDegrees = 0)

        anchor.clear()

        anchor.cropFor(0).shouldBeNull()
    }

    private companion object {
        private const val TOLERANCE = 0.0001f
    }
}
