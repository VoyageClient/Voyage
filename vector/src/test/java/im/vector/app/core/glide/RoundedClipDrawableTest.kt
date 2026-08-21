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
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoundedClipDrawableTest {

    private fun render(oval: Boolean, cornerPercent: Float = 0f): Bitmap {
        val size = 64
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        RoundedClipDrawable(ColorDrawable(Color.RED), cornerPercent, oval)
                .apply { setBounds(0, 0, size, size) }
                .draw(canvas)
        return bitmap
    }

    @Test
    fun `an oval clip erases the corners and keeps the centre`() {
        val bitmap = render(oval = true)

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
        val bitmap = render(oval = false, cornerPercent = 0.25f)

        bitmap.getPixel(0, 0) shouldBeEqualTo Color.TRANSPARENT
        bitmap.getPixel(32, 32) shouldBeEqualTo Color.RED
        // Mid-edge is inside a 16px-radius rounded rect.
        bitmap.getPixel(32, 0) shouldBeEqualTo Color.RED
        bitmap.getPixel(0, 32) shouldBeEqualTo Color.RED
    }
}
