/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZoomableImageViewResolutionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val view = ZoomableImageView(context).apply { layout(0, 0, 256, 128) }

    @Test
    fun `a photo loses detail at the selected resolution and recovers when sending the original`() {
        val source = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply { density = Bitmap.DENSITY_NONE }
        Stripes().apply { setBounds(0, 0, 64, 32) }.draw(Canvas(source))
        view.setImageDrawable(BitmapDrawable(context.resources, source))
        assertHasStripes(draw())

        view.contentSizeOverride = 8 to 4

        assertBlurred(draw())
        view.contentSizeOverride = null
        assertHasStripes(draw())
    }

    @Test
    fun `every animated frame is downscaled without replacing or stopping the drawable`() {
        val animation = Stripes().apply { start() }
        view.setImageDrawable(animation)
        view.contentSizeOverride = 8 to 4
        assertBlurred(draw())

        animation.first = Color.RED
        animation.second = Color.BLUE
        animation.invalidateSelf()
        val next = draw().getPixel(128, 64)

        assertTrue(Color.red(next) in 120..135)
        assertTrue(Color.blue(next) in 120..135)
        assertEquals(0, Color.green(next))
        assertSame(animation, view.drawable)
        assertSame(view, animation.callback)
        assertTrue(animation.isRunning)
    }

    @Test
    fun `a paused frame redraws when resolution changes without reloading it`() {
        val animation = Stripes()
        view.setImageDrawable(animation)
        view.contentSizeOverride = 8 to 4
        assertBlurred(draw())

        view.contentSizeOverride = 64 to 32

        assertHasStripes(draw())
        assertSame(animation, view.drawable)
    }

    @Test
    fun `transparent animation frames clear the previous frame`() {
        val animation = Stripes()
        view.setImageDrawable(animation)
        view.contentSizeOverride = 8 to 4
        assertBlurred(draw())
        animation.first = Color.TRANSPARENT
        animation.second = Color.TRANSPARENT

        assertEquals(0, Color.alpha(draw().getPixel(128, 64)))
    }

    @Test
    fun `a running animation gets redraws even when its decoder does not invalidate the view`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            activity.get().setContentView(view)
            val animation = Stripes().apply { start() }
            view.setImageDrawable(animation)
            view.contentSizeOverride = 8 to 4
            draw()
            shadowOf(view).clearWasInvalidated()

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))

            assertTrue(shadowOf(view).wasInvalidated())
            animation.stop()
            draw()
            shadowOf(view).clearWasInvalidated()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            assertFalse(shadowOf(view).wasInvalidated())
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test
    fun `a native GIF keeps receiving redraws after changing compression resolution`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val bytes = Base64.getDecoder().decode(
                "R0lGODlhEAAIAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAAEAAIAAAIFQABCBxIsKDBgwgTKlzIsKHDhwgDAgAh+QQBFAABACwAAAAAEAAIAIEAAP8AAAAAAAAAAAAIFQABCBxIsKDBgwgTKlzIsKHDhwgDAgA7"
        )
        val animation = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) as AnimatedImageDrawable
        try {
            activity.get().setContentView(view)
            view.setImageDrawable(animation)
            animation.start()
            draw()
            view.contentSizeOverride = 8 to 4
            draw()
            shadowOf(view).clearWasInvalidated()

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))

            assertTrue(animation.isRunning)
            assertTrue(shadowOf(view).wasInvalidated())
            assertSame(animation, view.drawable)
        } finally {
            animation.stop()
            activity.pause().stop().destroy()
        }
    }

    private fun draw(): Bitmap = Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    private fun assertBlurred(bitmap: Bitmap) {
        assertTrue(Color.red(bitmap.getPixel(128, 64)) in 120..135)
        assertTrue(Color.red(bitmap.getPixel(132, 64)) in 120..135)
    }

    private fun assertHasStripes(bitmap: Bitmap) {
        assertTrue(Color.red(bitmap.getPixel(129, 64)) < 80)
        assertTrue(Color.red(bitmap.getPixel(133, 64)) > 175)
    }

    private class Stripes : Drawable(), Animatable {
        var first = Color.BLACK
        var second = Color.WHITE
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        private var playing = false
        override fun getIntrinsicWidth() = 64
        override fun getIntrinsicHeight() = 32
        override fun draw(canvas: Canvas) {
            bitmap.setPixels(IntArray(64 * 32) { if (it % 2 == 0) first else second }, 0, 64, 0, 0, 64, 32)
            canvas.drawBitmap(bitmap, null, bounds, paint)
        }
        override fun start() { playing = true }
        override fun stop() { playing = false }
        override fun isRunning() = playing
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
