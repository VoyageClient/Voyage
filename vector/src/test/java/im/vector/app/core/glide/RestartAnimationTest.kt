/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import com.bumptech.glide.integration.webp.decoder.WebpDrawable
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.github.penfeizhou.animation.apng.APNGDrawable
import im.vector.app.test.fakes.FakeAnimatedDrawable
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RestartAnimationTest {

    @Test
    fun `a gif rewinds instead of carrying on from where the timeline left it`() {
        val drawable = mockk<GifDrawable>(relaxed = true)

        drawable.restartAnimation()

        verify { drawable.startFromFirstFrame() }
    }

    @Test
    fun `content already playing is left running rather than jerked back`() {
        // Glide's GifDrawable throws on a restart while running, and a second view binding the same
        // cached drawable — the open transition and the viewer behind it — would otherwise rewind it.
        val drawable = mockk<GifDrawable>(relaxed = true) {
            every { isRunning } returns true
        }

        drawable.restartAnimation()

        verify(exactly = 0) { drawable.startFromFirstFrame() }
    }

    @Test
    fun `an animated webp rewinds too`() {
        val drawable = mockk<WebpDrawable>(relaxed = true)

        drawable.restartAnimation()

        verify { drawable.startFromFirstFrame() }
    }

    @Test
    fun `an APNG is reset before it plays`() {
        val drawable = mockk<APNGDrawable>(relaxed = true)
        justRun { drawable.reset() }

        drawable.restartAnimation()

        verifyOrder {
            drawable.reset()
            drawable.start()
        }
    }

    @Test
    fun `anything else animated is stopped and started`() {
        val drawable = FakeAnimatedDrawable()
        drawable.start()

        drawable.restartAnimation()

        drawable.isRunning shouldBeEqualTo true
    }

    @Test
    fun `the listener rewinds the resource and leaves the target to display it`() {
        val drawable = mockk<GifDrawable>(relaxed = true)

        val handled = RestartAnimationListener.onResourceReady(drawable, "model", null, DataSource.MEMORY_CACHE, true)

        verify { drawable.startFromFirstFrame() }
        handled shouldBeEqualTo false
    }

    @Test
    fun `a still image is left alone`() {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val drawable = BitmapDrawable(RuntimeEnvironment.getApplication().resources, bitmap)

        drawable.restartAnimation()

        drawable.bitmap shouldBeEqualTo bitmap
    }
}
