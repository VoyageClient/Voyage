/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.drawable.Animatable
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.resource.gif.GifOptions
import com.github.penfeizhou.animation.apng.APNGDrawable
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AnimatedDrawableDecoderTest {

    private val decoder = AnimatedDrawableDecoder()

    private fun anApng() = ByteBuffer.wrap(javaClass.getResourceAsStream("/an_animated.png")!!.readBytes())

    private fun decode(options: Options) = decoder.decode(anApng(), 1, 1, options)!!.get()

    @Test
    fun `an APNG is claimed from Glide's still decoders, which miss some of them`() {
        decoder.handles(anApng(), Options()) shouldBeEqualTo true

        decode(Options()) shouldBeInstanceOf APNGDrawable::class
    }

    @Test
    fun `a still request yields a drawable that does not play itself once visible`() {
        val drawable = decode(Options().set(GifOptions.DISABLE_ANIMATION, true))

        drawable.setVisible(true, false)

        (drawable as Animatable).isRunning shouldBeEqualTo false
    }

    @Test
    fun `each bind gets its own drawable, so views cannot fight over one decoder`() {
        val resource = decoder.decode(anApng(), 1, 1, Options())!!

        (resource.get() === resource.get()) shouldBeEqualTo false
    }
}
