/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.graphics.drawable.Drawable
import android.widget.ImageView
import im.vector.app.core.glide.AnimatedContentImageViewTarget
import im.vector.app.core.glide.GlideRequest
import im.vector.app.core.glide.RestartAnimationListener
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.test.fakes.FakeAnimatedDrawable
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImageContentRendererAnimationTest {

    private val imageView = ImageView(RuntimeEnvironment.getApplication())
    private val request = mockk<GlideRequest<Drawable>>(relaxed = true).also {
        every { it.addListener(any()) } returns it
    }

    private val vectorPreferences = mockk<VectorPreferences>()

    private val imageContentRenderer = ImageContentRenderer(
            localFilesHelper = mockk(relaxed = true),
            activeSessionHolder = mockk(relaxed = true),
            dimensionConverter = mockk(relaxed = true),
            vectorPreferences = vectorPreferences,
    )

    private fun animates(mode: ImageContentRenderer.Mode) = imageContentRenderer.animates(mode)

    private fun loadWith(mode: ImageContentRenderer.Mode) = with(imageContentRenderer) {
        request.intoView(imageView, animates(mode))
    }

    @Test
    fun `a thumbnail is held on its first frame, even when it is a whole animated file from an encrypted room`() {
        val target = slot<AnimatedContentImageViewTarget>()
        every { request.into(capture(target)) } answers { target.captured }
        val drawable = FakeAnimatedDrawable()

        loadWith(ImageContentRenderer.Mode.THUMBNAIL)
        target.captured.onResourceReady(drawable, null)

        drawable.isRunning shouldBeEqualTo false
        imageView.drawable shouldBeEqualTo drawable
    }

    @Test
    fun `autoplay asks for the animated thumbnail mode, which loads into the view itself`() {
        every { vectorPreferences.autoplayAnimatedImages() } returns true

        loadWith(ImageContentRenderer.Mode.ANIMATED_THUMBNAIL)

        verify { request.into(imageView) }
    }

    @Test
    fun `content that animates rewinds every time it is bound`() {
        every { vectorPreferences.autoplayAnimatedImages() } returns true

        loadWith(ImageContentRenderer.Mode.ANIMATED_THUMBNAIL)

        verify { request.addListener(RestartAnimationListener) }
    }

    @Test
    fun `frozen content has nothing to rewind`() {
        loadWith(ImageContentRenderer.Mode.THUMBNAIL)

        verify(exactly = 0) { request.addListener(any()) }
    }

    @Test
    fun `stickers follow the autoplay setting, having no still variant to fall back on`() {
        every { vectorPreferences.autoplayAnimatedImages() } returns true
        animates(ImageContentRenderer.Mode.STICKER) shouldBeEqualTo true

        every { vectorPreferences.autoplayAnimatedImages() } returns false
        animates(ImageContentRenderer.Mode.STICKER) shouldBeEqualTo false
    }

    @Test
    fun `a sticker is held on its first frame when autoplay is off`() {
        every { vectorPreferences.autoplayAnimatedImages() } returns false
        val target = slot<AnimatedContentImageViewTarget>()
        every { request.into(capture(target)) } answers { target.captured }
        val drawable = FakeAnimatedDrawable()

        loadWith(ImageContentRenderer.Mode.STICKER)
        target.captured.onResourceReady(drawable, null)

        drawable.isRunning shouldBeEqualTo false
    }

    @Test
    fun `the media viewer plays whatever it is opened on`() {
        every { vectorPreferences.autoplayAnimatedImages() } returns false

        animates(ImageContentRenderer.Mode.FULL_SIZE) shouldBeEqualTo true
    }

    @Test
    fun `a thumbnail never animates, whatever autoplay says`() {
        every { vectorPreferences.autoplayAnimatedImages() } returns true

        animates(ImageContentRenderer.Mode.THUMBNAIL) shouldBeEqualTo false
    }
}
