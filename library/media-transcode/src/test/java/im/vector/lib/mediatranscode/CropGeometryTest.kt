/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterThan
import org.amshove.kluent.shouldBeLessOrEqualTo
import org.junit.Test
import kotlin.math.abs

class CropGeometryTest {

    private val whole = floatArrayOf(0f, 0f, 1f, 1f)

    @Test
    fun `unrotated whole frame samples the texture corner for corner`() {
        // The classic CTS quad: bottom-left (0,0), bottom-right (1,0), top-left (0,1), top-right (1,1).
        CropGeometry.textureCoords(whole, 0) shouldBeCloseTo floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    }

    @Test
    fun `a quarter turn puts the coded bottom edge on the display left edge`() {
        // Rotating clockwise sends the coded bottom-left corner to the display top-left, so that is
        // the corner the top-left of the quad must sample.
        val coords = CropGeometry.textureCoords(whole, 90)
        coords[4] shouldBeCloseToFloat 0f
        coords[5] shouldBeCloseToFloat 0f
    }

    @Test
    fun `every rotation is a permutation of the same four corners`() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            val corners = CropGeometry.textureCoords(whole, rotation).toList().chunked(2).map { it[0] to it[1] }
            corners.toSet().size shouldBeEqualTo 4
            corners.forEach { (s, t) ->
                (abs(s) < TOLERANCE || abs(s - 1f) < TOLERANCE) shouldBeEqualTo true
                (abs(t) < TOLERANCE || abs(t - 1f) < TOLERANCE) shouldBeEqualTo true
            }
        }
    }

    @Test
    fun `unrotate is the inverse of the rotation it names`() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            // Rotating a display point back and forth has to land where it started.
            val coded = CropGeometry.unrotate(0.25f, 0.75f, rotation)
            val roundTripped = CropGeometry.unrotate(coded[0], coded[1], (360 - rotation) % 360)
            roundTripped[0] shouldBeCloseToFloat 0.25f
            roundTripped[1] shouldBeCloseToFloat 0.75f
        }
    }

    @Test
    fun `unrotate normalises rotations outside a single turn`() {
        CropGeometry.unrotate(0.3f, 0.6f, 450) shouldBeCloseTo CropGeometry.unrotate(0.3f, 0.6f, 90)
        CropGeometry.unrotate(0.3f, 0.6f, -90) shouldBeCloseTo CropGeometry.unrotate(0.3f, 0.6f, 270)
    }

    @Test
    fun `a crop takes only its own half of the frame`() {
        val leftHalf = floatArrayOf(0f, 0f, 0.5f, 1f)
        val coords = CropGeometry.textureCoords(leftHalf, 0)
        // Both right-hand corners of the quad now sample the middle of the texture.
        coords[2] shouldBeCloseToFloat 0.5f
        coords[6] shouldBeCloseToFloat 0.5f
    }

    @Test
    fun `output dimensions are multiples of 16`() {
        val output = CropGeometry.outputFor(1920, 1080, floatArrayOf(0f, 0f, 0.37f, 0.61f))
        (output.width % CropGeometry.ALIGNMENT) shouldBeEqualTo 0
        (output.height % CropGeometry.ALIGNMENT) shouldBeEqualTo 0
    }

    @Test
    fun `alignment never rounds the output up past the source`() {
        val output = CropGeometry.outputFor(1080, 1080, whole)
        output.width shouldBeLessOrEqualTo 1080
        output.height shouldBeLessOrEqualTo 1080
    }

    @Test
    fun `the crop is narrowed so alignment cannot stretch the picture`() {
        // 300x200 of a 1000x1000 frame aligns down to 288x192, a slightly different aspect ratio.
        val requested = floatArrayOf(0f, 0f, 0.3f, 0.2f)
        val output = CropGeometry.outputFor(1000, 1000, requested)
        output.width shouldBeEqualTo 288
        output.height shouldBeEqualTo 192
        val cropAspect = (output.crop[2] - output.crop[0]) / (output.crop[3] - output.crop[1])
        cropAspect shouldBeCloseToFloat output.width.toFloat() / output.height
    }

    @Test
    fun `a crop below the minimum is lifted on both axes at once`() {
        // 100x50 would leave the height under the floor; squaring it up would squash the picture.
        val output = CropGeometry.outputFor(1000, 1000, floatArrayOf(0f, 0f, 0.1f, 0.05f))
        output.height.toFloat() shouldBeGreaterThan CropGeometry.MIN_DIMENSION - CropGeometry.ALIGNMENT
        (output.width.toFloat() / output.height) shouldBeCloseToFloat 2f
    }

    @Test
    fun `narrowing the crop keeps it centred on what the user chose`() {
        val requested = floatArrayOf(0.1f, 0.2f, 0.3f, 0.3f)
        val output = CropGeometry.outputFor(1000, 1000, requested)
        (output.crop[0] + output.crop[2]) / 2f shouldBeCloseToFloat 0.2f
        (output.crop[1] + output.crop[3]) / 2f shouldBeCloseToFloat 0.25f
        // Narrowed, never widened: nothing outside the chosen rectangle can appear.
        output.crop[0] shouldBeGreaterThan requested[0] - TOLERANCE
        output.crop[2] shouldBeLessOrEqualTo requested[2] + TOLERANCE
    }

    @Test
    fun `an explicit size is taken as given, and the crop left alone`() {
        val requested = floatArrayOf(0f, 0f, 1f, 0.5f)
        val output = CropGeometry.outputFor(1920, 1080, requested, targetWidth = 640, targetHeight = 480)
        output.width shouldBeEqualTo 640
        output.height shouldBeEqualTo 480
        output.crop shouldBeCloseTo requested
    }

    @Test
    fun `a tiny crop still produces an encodable frame, at its own shape`() {
        // 19x11 of a 1080p frame, lifted off the floor without becoming a square.
        val output = CropGeometry.outputFor(1920, 1080, floatArrayOf(0f, 0f, 0.01f, 0.01f))
        output.height.toFloat() shouldBeGreaterThan CropGeometry.MIN_DIMENSION - CropGeometry.ALIGNMENT
        output.width shouldBeGreaterThan output.height
        val cropAspect = (output.crop[2] - output.crop[0]) * 1920 / ((output.crop[3] - output.crop[1]) * 1080)
        cropAspect shouldBeCloseToFloat output.width.toFloat() / output.height
    }

    @Test
    fun `a source smaller than the minimum is not inflated past itself`() {
        // 64x64 cannot honour a 128 floor; the ceiling wins so the encoder is never asked to upscale.
        val output = CropGeometry.outputFor(64, 64, whole)
        output.width shouldBeEqualTo 64
        output.height shouldBeEqualTo 64
    }

    private infix fun FloatArray.shouldBeCloseTo(expected: FloatArray) {
        size shouldBeEqualTo expected.size
        forEachIndexed { index, value -> value shouldBeCloseToFloat expected[index] }
    }

    private infix fun Float.shouldBeCloseToFloat(expected: Float) {
        (abs(this - expected) < TOLERANCE) shouldBeEqualTo true
    }

    companion object {
        private const val TOLERANCE = 0.0005f
    }
}
