/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.avatar.effect

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.ViewGroup
import android.widget.ImageView
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarEffectDrawablesTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `the size the caller is decoding at wins over guesses`() {
        // A view is usually unmeasured when its placeholder is wrapped. Guessing there and correcting
        // on a later bind renders every avatar twice, at two different sizes.
        val unmeasured = ImageView(context)

        val shaped = AvatarEffectDrawables.wrap(source(), AvatarEffect.CUBE, unmeasured, requestedPx = 168)

        shaped.shouldBeInstanceOf<AnimatedAvatarDrawable>()
        (shaped as AnimatedAvatarDrawable).sizePx shouldBeEqualTo 160
    }

    @Test
    fun `an unmeasured view falls back to its layout size before guessing`() {
        val unmeasured = ImageView(context).apply { layoutParams = ViewGroup.LayoutParams(128, 128) }

        val shaped = AvatarEffectDrawables.wrap(source(), AvatarEffect.CUBE, unmeasured)

        (shaped as AnimatedAvatarDrawable).sizePx shouldBeEqualTo 128
    }

    @Test
    fun `sizes land on buckets, rounded to the nearest rather than up`() {
        // Rounding 168 up to 192 is a third more pixels to rasterise on every single frame.
        listOf(168 to 160, 96 to 96, 56 to 64, 20 to 48, 400 to 256).forEach { (requested, expected) ->
            val shaped = AvatarEffectDrawables.wrap(source(), AvatarEffect.CUBE, null, requestedPx = requested)

            (shaped as AnimatedAvatarDrawable).sizePx shouldBeEqualTo expected
        }
    }

    @Test
    fun `rebinding the same view reuses its drawable instead of building another`() {
        val view = ImageView(context).apply { layoutParams = ViewGroup.LayoutParams(96, 96) }

        val first = AvatarEffectDrawables.wrap(source(), AvatarEffect.CUBE, view)
        val second = AvatarEffectDrawables.wrap(source(), AvatarEffect.CUBE, view)

        (first === second) shouldBeEqualTo true
    }

    private fun source(): BitmapDrawable {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        return BitmapDrawable(context.resources, bitmap)
    }
}
