/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import im.vector.app.features.settings.AvatarShape
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShapeClipDrawableTest {

    private fun render(shape: AvatarShape): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        ShapeClipDrawable(ColorDrawable(Color.RED), shape)
                .apply { setBounds(0, 0, SIZE, SIZE) }
                .draw(Canvas(bitmap))
        return bitmap
    }

    @Test
    fun `an oval clip erases the corners and keeps the centre`() {
        val bitmap = render(AvatarShape.CIRCLE)

        bitmap.getPixel(0, 0) shouldBeEqualTo Color.TRANSPARENT
        bitmap.getPixel(63, 0) shouldBeEqualTo Color.TRANSPARENT
        bitmap.getPixel(0, 63) shouldBeEqualTo Color.TRANSPARENT
        bitmap.getPixel(63, 63) shouldBeEqualTo Color.TRANSPARENT
        bitmap.getPixel(32, 32) shouldBeEqualTo Color.RED
        // On the oval's edge, so still fully covered.
        bitmap.getPixel(32, 1) shouldBeEqualTo Color.RED
    }

    @Test
    fun `a rounded-rect clip erases only the corners`() {
        val bitmap = render(AvatarShape.ROUNDED)

        bitmap.getPixel(0, 0) shouldBeEqualTo Color.TRANSPARENT
        bitmap.getPixel(32, 32) shouldBeEqualTo Color.RED
        bitmap.getPixel(32, 0) shouldBeEqualTo Color.RED
        bitmap.getPixel(0, 32) shouldBeEqualTo Color.RED
    }

    @Test
    fun `a square clip keeps every pixel`() {
        val bitmap = render(AvatarShape.SQUARE)

        bitmap.getPixel(0, 0) shouldBeEqualTo Color.RED
        bitmap.getPixel(63, 63) shouldBeEqualTo Color.RED
    }

    @Test
    fun `every polygonal shape keeps the centre and drops the top-left corner`() {
        for (shape in AvatarShape.STATIC) {
            if (shape == AvatarShape.SQUARE) continue
            val bitmap = render(shape)

            withClue(shape) { bitmap.getPixel(SIZE / 2, SIZE / 2) shouldBeEqualTo Color.RED }
            withClue(shape) { bitmap.getPixel(0, 0) shouldBeEqualTo Color.TRANSPARENT }
        }
    }

    @Test
    fun `a triangle keeps its apex column and drops its top corners`() {
        val bitmap = render(AvatarShape.TRIANGLE)

        // The triangle is scaled uniformly and centred, so its base sits a little above the bottom.
        bitmap.getPixel(SIZE / 2, SIZE - 7) shouldBeEqualTo Color.RED
        bitmap.getPixel(0, 0) shouldBeEqualTo Color.TRANSPARENT
        bitmap.getPixel(SIZE - 1, 0) shouldBeEqualTo Color.TRANSPARENT
        bitmap.getPixel(6, SIZE - 7) shouldBeEqualTo Color.RED
    }

    private fun <T> withClue(clue: Any, block: () -> T): T = try {
        block()
    } catch (error: AssertionError) {
        throw AssertionError("$clue: ${error.message}", error)
    }

    private companion object {
        const val SIZE = 64
    }
}
