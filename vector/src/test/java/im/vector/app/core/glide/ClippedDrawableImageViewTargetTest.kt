/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import im.vector.app.test.fakes.FakeAnimatedDrawable
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ClippedDrawableImageViewTargetTest {

    private val context = RuntimeEnvironment.getApplication()
    private val imageView = ImageView(context)

    @Test
    fun `a round avatar clips animated content, which no bitmap transformation can shape`() {
        val target = ClippedDrawableImageViewTarget(imageView, cornerPercent = 0f, oval = true)

        target.onResourceReady(FakeAnimatedDrawable(), null)

        imageView.drawable shouldBeInstanceOf RoundedClipDrawable::class
    }

    @Test
    fun `a rounded square avatar clips animated content too`() {
        val target = ClippedDrawableImageViewTarget(imageView, cornerPercent = 0.2f, oval = false)

        target.onResourceReady(FakeAnimatedDrawable(), null)

        imageView.drawable shouldBeInstanceOf RoundedClipDrawable::class
    }

    @Test
    fun `a square avatar has nothing to clip`() {
        val target = ClippedDrawableImageViewTarget(imageView, cornerPercent = 0f, oval = false)
        val drawable = FakeAnimatedDrawable()

        target.onResourceReady(drawable, null)

        imageView.drawable shouldBeEqualTo drawable
    }

    @Test
    fun `content Glide already shaped passes through`() {
        val target = ClippedDrawableImageViewTarget(imageView, cornerPercent = 0.2f, oval = false)
        val drawable = BitmapDrawable(context.resources, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))

        target.onResourceReady(drawable, null)

        imageView.drawable shouldBeEqualTo drawable
    }

    @Test
    fun `a cached animated avatar is held on its first frame once autoplay is off, and still shown`() {
        val target = ClippedDrawableImageViewTarget(imageView, cornerPercent = 0.2f, oval = false, animate = false)
        val drawable = FakeAnimatedDrawable()

        target.onResourceReady(drawable, null)

        drawable.isRunning shouldBeEqualTo false
        (imageView.drawable as RoundedClipDrawable).isRunning shouldBeEqualTo false
    }
}
