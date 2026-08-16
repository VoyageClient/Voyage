/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.request.FutureTarget
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.glide.GlideRequest
import im.vector.app.core.glide.GlideRequests
import im.vector.app.core.glide.ThumbnailAttempt
import im.vector.app.core.glide.ThumbnailVariants
import im.vector.app.features.settings.AvatarShape
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.test.fakes.FakeAnimatedDrawable
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import org.matrix.android.sdk.api.util.MatrixItem
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val AVATAR_MXC_URL = "mxc://example.org/media"
private const val STILL_URL = "https://example.org/thumbnail/example.org/media?width=250&height=250&method=scale"
private const val ANIMATED_URL = "$STILL_URL&animated=true"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AvatarThumbnailVariantsTest {

    private val context = RuntimeEnvironment.getApplication()

    private val contentUrlResolver = mockk<ContentUrlResolver> {
        every { resolveThumbnail(AVATAR_MXC_URL, any(), any(), any(), false) } returns STILL_URL
        every { resolveThumbnail(AVATAR_MXC_URL, any(), any(), any(), true) } returns ANIMATED_URL
        every { resolveThumbnail(null, any(), any(), any(), any()) } returns null
    }
    private val session = mockk<Session> {
        every { contentUrlResolver() } returns contentUrlResolver
    }
    private val activeSessionHolder = mockk<ActiveSessionHolder> {
        every { getSafeActiveSession() } returns session
    }
    private val vectorPreferences = mockk<VectorPreferences> {
        every { avatarShape() } returns AvatarShape.CIRCLE
    }
    private val thumbnailVariants = ThumbnailVariants()

    private val avatarRenderer = AvatarRenderer(
            activeSessionHolder = activeSessionHolder,
            matrixItemColorProvider = mockk(relaxed = true),
            dimensionConverter = mockk(relaxed = true),
            stringProvider = mockk(relaxed = true),
            vectorPreferences = vectorPreferences,
            twemojiProvider = mockk(relaxed = true),
            thumbnailVariants = thumbnailVariants,
    )

    private val matrixItem = MatrixItem.UserItem("@alice:example.org", "Alice", AVATAR_MXC_URL)

    private val request = mockk<GlideRequest<Drawable>>(relaxed = true).also {
        every { it.optionalTransform(any<Transformation<Bitmap>>()) } returns it
        every { it.placeholder(any<Drawable>()) } returns it
        every { it.onlyRetrieveFromCache(any()) } returns it
        every { it.dontAnimate() } returns it
        every { it.addListener(any()) } returns it
        every { it.error(any<RequestBuilder<Drawable>>()) } returns it
    }
    private val glideRequests = mockk<GlideRequests>(relaxed = true).also {
        every { it.load(any<String>()) } returns request
    }

    @Test
    fun `autoplay asks the server for the animated thumbnail`() {
        every { vectorPreferences.autoplayAnimatedImages() } returns true

        avatarRenderer.avatarAttempts(AVATAR_MXC_URL) shouldBeEqualTo listOf(
                ThumbnailAttempt(ANIMATED_URL, cacheOnly = false)
        )
    }

    @Test
    fun `turning autoplay off reuses an already cached animated thumbnail instead of downloading a still one`() {
        every { vectorPreferences.autoplayAnimatedImages() } returns false

        avatarRenderer.avatarAttempts(AVATAR_MXC_URL) shouldBeEqualTo listOf(
                ThumbnailAttempt(STILL_URL, cacheOnly = true),
                ThumbnailAttempt(ANIMATED_URL, cacheOnly = true),
                ThumbnailAttempt(STILL_URL, cacheOnly = false),
        )
    }

    @Test
    fun `both variants are resolved at the avatar thumbnail size`() {
        every { vectorPreferences.autoplayAnimatedImages() } returns false

        avatarRenderer.avatarAttempts(AVATAR_MXC_URL)

        verify { contentUrlResolver.resolveThumbnail(AVATAR_MXC_URL, 250, 250, ContentUrlResolver.ThumbnailMethod.SCALE, false) }
        verify { contentUrlResolver.resolveThumbnail(AVATAR_MXC_URL, 250, 250, ContentUrlResolver.ThumbnailMethod.SCALE, true) }
    }

    @Test
    fun `autoplay never resolves the still variant it cannot use`() {
        every { vectorPreferences.autoplayAnimatedImages() } returns true

        avatarRenderer.avatarAttempts(AVATAR_MXC_URL)

        verify(exactly = 0) { contentUrlResolver.resolveThumbnail(any(), any(), any(), any(), false) }
    }

    @Test
    fun `the variant that served last time is tried first, so the bind stays off the placeholder`() {
        every { vectorPreferences.autoplayAnimatedImages() } returns false
        thumbnailVariants.remember(AVATAR_MXC_URL, ANIMATED_URL)

        avatarRenderer.avatarAttempts(AVATAR_MXC_URL) shouldBeEqualTo listOf(
                ThumbnailAttempt(ANIMATED_URL, cacheOnly = true),
                ThumbnailAttempt(STILL_URL, cacheOnly = true),
                ThumbnailAttempt(STILL_URL, cacheOnly = false),
        )
    }

    @Test
    fun `a media nothing has served yet keeps the default order`() {
        every { vectorPreferences.autoplayAnimatedImages() } returns false
        thumbnailVariants.remember("mxc://example.org/other", ANIMATED_URL)

        avatarRenderer.avatarAttempts(AVATAR_MXC_URL)!!.first() shouldBeEqualTo
                ThumbnailAttempt(STILL_URL, cacheOnly = true)
    }

    @Test
    fun `an unresolvable avatar has nothing to load`() {
        every { vectorPreferences.autoplayAnimatedImages() } returns true

        avatarRenderer.avatarAttempts(null).shouldBeNull()
    }

    @Test
    fun `a signed out user has nothing to load`() {
        every { vectorPreferences.autoplayAnimatedImages() } returns true
        every { activeSessionHolder.getSafeActiveSession() } returns null

        avatarRenderer.avatarAttempts(AVATAR_MXC_URL).shouldBeNull()
    }

    @Test
    fun `a cached-drawable load never reaches the network, on any variant`() {
        every { vectorPreferences.autoplayAnimatedImages() } returns false
        every { request.submit() } returns mockk<FutureTarget<Drawable>> { every { get() } returns mockk(relaxed = true) }

        avatarRenderer.getCachedDrawable(glideRequests, matrixItem)

        verify(exactly = 0) { request.onlyRetrieveFromCache(false) }
        // The still and animated variants; the third attempt only differs by being allowed to download.
        verify(exactly = 2) { request.onlyRetrieveFromCache(true) }
    }

    @Test
    fun `rendering an avatar lets the last attempt download`() {
        every { vectorPreferences.autoplayAnimatedImages() } returns false

        avatarRenderer.render(glideRequests, matrixItem, mockk(relaxed = true))

        verify(exactly = 2) { request.onlyRetrieveFromCache(true) }
        verify(exactly = 1) { request.onlyRetrieveFromCache(false) }
    }

    @Test
    fun `an avatar is held on its first frame when autoplay is off`() {
        every { vectorPreferences.autoplayAnimatedImages() } returns false
        val imageView = ImageView(context)
        val drawable = FakeAnimatedDrawable()

        avatarRenderer.avatarTarget(imageView, matrixItem).onResourceReady(drawable, null)

        drawable.isRunning shouldBeEqualTo false
        imageView.drawable.shouldNotBeNull()
    }

    @Test
    fun `an avatar plays when autoplay is on`() {
        every { vectorPreferences.autoplayAnimatedImages() } returns true
        val imageView = ImageView(context)
        val drawable = FakeAnimatedDrawable()

        avatarRenderer.avatarTarget(imageView, matrixItem).onResourceReady(drawable, null)

        drawable.isRunning shouldBeEqualTo true
    }

    @Test
    fun `a space avatar is a rounded square whatever shape avatars are set to`() {
        every { vectorPreferences.autoplayAnimatedImages() } returns true
        every { vectorPreferences.avatarShape() } returns AvatarShape.SQUARE
        val imageView = ImageView(context)
        val space = MatrixItem.SpaceItem("!space:example.org", "Space", AVATAR_MXC_URL)

        avatarRenderer.avatarTarget(imageView, space).onResourceReady(FakeAnimatedDrawable(), null)

        // Rounded content is clipped by the view; a square avatar would leave it unclipped.
        imageView.clipToOutline shouldBeEqualTo true
    }
}
