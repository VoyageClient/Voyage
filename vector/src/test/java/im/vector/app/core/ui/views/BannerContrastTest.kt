/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import com.bumptech.glide.request.Request
import com.bumptech.glide.request.target.DrawableImageViewTarget
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import im.vector.app.core.extensions.backgroundCompat
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.features.media.MediaPlaceholderDrawable
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BannerContrastTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `isolated highlights do not turn dark backgrounds into dark controls`() {
        val pixels = IntArray(64) { Color.rgb(51, 51, 51) }
        pixels[0] = Color.WHITE
        val contrast = BannerContrast(pixels)
        assertFalse(contrast.prefersDark)
        assertTrue(contrast.whiteCoverage > 0.98)
        assertTrue(contrast.needsShadow)
    }

    @Test
    fun `isolated dark spots do not turn bright backgrounds into white controls`() {
        val pixels = IntArray(64) { Color.WHITE }
        pixels[0] = Color.BLACK
        assertTrue(BannerContrast(pixels).prefersDark)
    }

    @Test
    fun `mixed backgrounds keep white controls with a shadow`() {
        val contrast = BannerContrast(IntArray(64) { if (it % 2 == 0) Color.WHITE else Color.BLACK })
        assertFalse(contrast.prefersDark)
        assertTrue(contrast.needsShadow)
    }

    @Test
    fun `missing samples use white with a shadow`() {
        val contrast = BannerContrast(IntArray(0))
        assertFalse(contrast.prefersDark)
        assertTrue(contrast.needsShadow)
    }

    @Test
    fun `middle gray keeps white even when black has more contrast`() {
        for (level in listOf(128, 150, 180, 190)) {
            val contrast = solid(level)
            assertFalse("gray=$level", contrast.prefersDark)
        }
    }

    @Test
    fun `dark controls require a background where white washes out`() {
        val contrast = solid(220)
        assertTrue(contrast.prefersDark)
        assertEquals(0.0, contrast.whiteCoverage, 0.0)
    }

    @Test
    fun `the first loaded image gets its final colors immediately`() {
        val color = StableBannerColor()
        assertFalse(color.choose(emptyList()))
        assertTrue(color.choose(listOf(solid(230)), reset = true))
        assertFalse(color.choose(listOf(solid(51)), reset = true))
    }

    @Test
    fun `small brightness changes keep the established color`() {
        val color = StableBannerColor()
        assertTrue(color.choose(listOf(solid(230))))
        assertTrue(color.choose(listOf(solid(190))))
        assertTrue(color.choose(listOf(solid(200))))
        assertFalse(color.choose(listOf(solid(160))))
        assertFalse(color.choose(listOf(solid(190))))
        assertTrue(color.choose(listOf(solid(220))))
    }

    @Test
    fun `a new image must qualify for dark controls again`() {
        val color = StableBannerColor()
        assertTrue(color.choose(listOf(solid(230))))
        assertFalse(color.choose(listOf(solid(190)), reset = true))
    }

    @Test
    fun `every control area must be clearly bright before choosing dark`() {
        assertFalse(StableBannerColor().choose(listOf(solid(230), solid(180))))
        assertTrue(StableBannerColor().choose(listOf(solid(230), solid(220))))
    }

    private fun solid(level: Int) = BannerContrast(IntArray(64) { Color.rgb(level, level, level) })

    @Test
    fun `cached luminance agrees with AndroidX contrast calculations`() {
        val random = Random(42)
        repeat(100) {
            val pixels = IntArray(64) { Color.BLACK or random.nextInt(0x1000000) }
            val contrast = BannerContrast(pixels)
            val white = pixels.map { ColorUtils.calculateContrast(Color.WHITE, it) }.sorted()
            val dark = pixels.map { ColorUtils.calculateContrast(BannerContrast.DARK, it) }
            val luminance = pixels.map { ColorUtils.calculateLuminance(it) }.sorted()
            assertEquals(white.count { it >= 3.0 } / 64.0, contrast.whiteCoverage, 0.0)
            assertEquals(dark.count { it >= 3.0 } / 64.0, contrast.darkCoverage, 0.0)
            assertEquals(white[6], contrast.lowerWhite, 1e-9)
            assertEquals(luminance[6], contrast.lowerLuminance, 1e-9)
            assertEquals(luminance[32], contrast.medianLuminance, 1e-9)
        }
    }

    @Test
    fun `static bitmaps are sampled without redrawing or recycling the image`() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        var draws = 0
        val drawable = object : BitmapDrawable(context.resources, bitmap) {
            override fun draw(canvas: Canvas) {
                draws++
                super.draw(canvas)
            }
        }
        val sampler = BannerImageSampler(Color.BLACK)
        assertTrue(sampler.render(imageView(drawable)))
        assertTrue(sampler.sample(RectF(0f, 0f, 100f, 100f)).prefersDark)
        assertEquals(0, draws)
        sampler.release()
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }

    @Test
    fun `direct bitmap samples composite transparency over the view background`() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val view = imageView(BitmapDrawable(context.resources, bitmap))
        view.backgroundCompat = ColorDrawable(Color.WHITE)
        val sampler = BannerImageSampler(Color.BLACK)
        assertTrue(sampler.render(view))
        assertEquals(Color.WHITE, sampler.sample(RectF(0f, 0f, 100f, 100f)).pixels[0])
        sampler.release()
        bitmap.recycle()
    }

    @Test
    fun `transparent image pixels use the displayed background`() {
        val view = imageView(ColorDrawable(Color.TRANSPARENT))
        val sampler = BannerImageSampler(Color.WHITE)
        assertTrue(sampler.render(view))
        assertTrue(sampler.sample(RectF(0f, 0f, 100f, 100f)).prefersDark)
        sampler.release()
    }

    @Test
    fun `sampling follows the image matrix and center crop`() {
        val bitmap = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        for (x in 100 until 200) for (y in 0 until 100) bitmap.setPixel(x, y, Color.BLACK)
        val view = imageView(BitmapDrawable(context.resources, bitmap), ImageView.ScaleType.CENTER_CROP)
        val sampler = BannerImageSampler(Color.WHITE)
        assertTrue(sampler.render(view))
        val contrast = sampler.sample(RectF(0f, 0f, 100f, 100f))
        assertFalse("matrix=${view.imageMatrix} pixels=${contrast.pixels.toList()}", contrast.prefersDark)
        sampler.release()
        bitmap.recycle()
    }

    @Test
    fun `centered bitmap drawables leave their uncovered background unsampled as image pixels`() {
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        bitmap.density = context.resources.displayMetrics.densityDpi
        bitmap.eraseColor(Color.WHITE)
        val drawable = BitmapDrawable(context.resources, bitmap).apply { gravity = Gravity.CENTER }
        val sampler = BannerImageSampler(Color.BLACK)
        assertTrue(sampler.render(imageView(drawable, ImageView.ScaleType.FIT_XY)))
        assertFalse(sampler.sample(RectF(0f, 0f, 20f, 100f)).prefersDark)
        assertTrue(sampler.sample(RectF(45f, 45f, 55f, 55f)).prefersDark)
        sampler.release()
        bitmap.recycle()
    }

    @Test
    fun `image animation updates without replacing the drawable`() {
        val drawable = ColorDrawable(Color.BLACK)
        val view = imageView(drawable)
        val sampler = BannerImageSampler(Color.BLACK)
        assertTrue(sampler.render(view))
        assertFalse(sampler.sample(RectF(0f, 0f, 100f, 100f)).prefersDark)
        drawable.color = Color.WHITE
        assertTrue(sampler.render(view))
        assertTrue(sampler.sample(RectF(0f, 0f, 100f, 100f)).prefersDark)
        sampler.release()
    }

    @Test
    fun `status and controls can choose different colors`() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        for (x in 0 until 100) for (y in 0 until 20) bitmap.setPixel(x, y, Color.WHITE)
        val sampler = BannerImageSampler(Color.BLACK)
        assertTrue(sampler.render(imageView(BitmapDrawable(context.resources, bitmap))))
        assertTrue(sampler.sample(RectF(0f, 0f, 100f, 20f)).prefersDark)
        assertFalse(sampler.sample(RectF(0f, 30f, 24f, 54f)).prefersDark)
        sampler.release()
        bitmap.recycle()
    }

    @Test
    fun `regions outside the scrolled banner use the page background`() {
        val sampler = BannerImageSampler(Color.WHITE)
        assertTrue(sampler.render(imageView(ColorDrawable(Color.BLACK))))
        assertTrue(sampler.sample(RectF(0f, 110f, 100f, 130f)).prefersDark)
        assertEquals(64, sampler.sample(RectF(0f, 0f, 100f, 100f)).pixels.size)
        sampler.release()
    }

    @Test
    fun `profile opens before the banner finishes loading`() {
        val screen = profile()
        var revealed = false
        screen.helper.revealAfterLayout { revealed = true }
        screen.root.viewTreeObserver.dispatchOnPreDraw()
        assertTrue(revealed)
        every { screen.request.isRunning } returns false
        every { screen.request.isComplete } returns true
        screen.banner.setImageDrawable(ColorDrawable(Color.WHITE))
        layout(screen.root)
        screen.root.viewTreeObserver.dispatchOnPreDraw()
        assertTrue(WindowInsetsControllerCompat(screen.activity.window, screen.activity.window.decorView).isAppearanceLightStatusBars)
        screen.helper.restore()
    }

    @Test
    fun `profile opens with theme controls while its banner is still a placeholder`() {
        val screen = profile()
        val originalColor = iconColor(screen.toolbar.navigationIcon!!)
        screen.banner.setImageDrawable(mockk<MediaPlaceholderDrawable>(relaxed = true))
        layout(screen.root)
        var revealed = false
        screen.helper.revealAfterLayout { revealed = true }
        screen.root.viewTreeObserver.dispatchOnPreDraw()
        assertTrue(revealed)
        assertEquals(originalColor, iconColor(screen.toolbar.navigationIcon!!))
        screen.helper.restore()
    }

    @Test
    fun `a crossfading banner is sampled only once it settles`() {
        val screen = profile()
        val insets = WindowInsetsControllerCompat(screen.activity.window, screen.activity.window.decorView)
        val originalColor = iconColor(screen.toolbar.navigationIcon!!)
        // An unstarted crossfade draws only its first layer, which here is bright.
        screen.banner.setImageDrawable(TransitionDrawable(arrayOf(ColorDrawable(Color.WHITE), ColorDrawable(Color.BLACK))))
        layout(screen.root)
        screen.helper.revealAfterLayout {}
        screen.root.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(originalColor, iconColor(screen.toolbar.navigationIcon!!))
        assertFalse(insets.isAppearanceLightStatusBars)
        screen.banner.setImageDrawable(ColorDrawable(Color.WHITE))
        layout(screen.root)
        screen.root.viewTreeObserver.dispatchOnPreDraw()
        assertTrue(insets.isAppearanceLightStatusBars)
        screen.helper.restore()
    }

    @Test
    fun `failed banner loads release the profile`() {
        val screen = profile()
        every { screen.request.isRunning } returns false
        var revealed = false
        screen.helper.revealAfterLayout { revealed = true }
        screen.root.viewTreeObserver.dispatchOnPreDraw()
        assertTrue(revealed)
        screen.helper.restore()
    }

    @Test
    fun `profiles without a banner ignore the hidden image layout request`() {
        val screen = profile()
        screen.banner.visibility = View.GONE
        screen.banner.setImageDrawable(null)
        DrawableImageViewTarget(screen.banner).request = null
        screen.helper.update(false)
        layout(screen.root)
        screen.banner.requestLayout()
        assertTrue(screen.banner.isLayoutRequested)
        var revealed = false
        screen.helper.revealAfterLayout { revealed = true }
        screen.root.viewTreeObserver.dispatchOnPreDraw()
        assertTrue(revealed)
        screen.helper.restore()
    }

    @Test
    fun `removing a banner after opening keeps the profile revealed`() {
        val screen = profile()
        var revealed = false
        screen.helper.revealAfterLayout { revealed = true }
        screen.root.viewTreeObserver.dispatchOnPreDraw()
        assertTrue(revealed)
        screen.banner.visibility = View.GONE
        screen.banner.setImageDrawable(null)
        DrawableImageViewTarget(screen.banner).request = null
        screen.helper.update(false)
        layout(screen.root)
        screen.root.viewTreeObserver.dispatchOnPreDraw()
        assertTrue(revealed)
        screen.helper.restore()
    }

    @Test
    fun `collapsed profiles reveal without waiting for the offscreen banner`() {
        val screen = profile()
        screen.helper.onOffsetChanged(AppBarLayout(screen.root.context), -1000)
        var revealed = false
        screen.helper.revealAfterLayout { revealed = true }
        screen.root.viewTreeObserver.dispatchOnPreDraw()
        assertTrue(revealed)
        screen.helper.restore()
    }

    @Test
    fun `late banner colors fade from the existing button color`() {
        val screen = profile()
        screen.helper.revealAfterLayout {}
        screen.root.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(Color.WHITE, iconColor(screen.toolbar.navigationIcon!!))
        screen.banner.setImageDrawable(ColorDrawable(Color.WHITE))
        layout(screen.root)
        screen.root.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(Color.WHITE, iconColor(screen.toolbar.navigationIcon!!))
        val animator = glyphAnimator(screen.helper)
        animator.currentPlayTime = animator.duration / 2
        val middle = iconColor(screen.toolbar.navigationIcon!!)
        assertTrue(middle != Color.WHITE && middle != BannerContrast.DARK)
        animator.end()
        assertEquals(BannerContrast.DARK, iconColor(screen.toolbar.navigationIcon!!))
        screen.helper.restore()
    }

    @Test
    fun `a new banner continues from an interrupted button color fade`() {
        val screen = profile()
        screen.helper.revealAfterLayout {}
        screen.root.viewTreeObserver.dispatchOnPreDraw()
        screen.banner.setImageDrawable(ColorDrawable(Color.WHITE))
        layout(screen.root)
        screen.root.viewTreeObserver.dispatchOnPreDraw()
        val animator = glyphAnimator(screen.helper)
        animator.currentPlayTime = animator.duration / 2
        val middle = iconColor(screen.toolbar.navigationIcon!!)
        screen.banner.setImageDrawable(ColorDrawable(Color.BLACK))
        layout(screen.root)
        screen.root.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(middle, iconColor(screen.toolbar.navigationIcon!!))
        glyphAnimator(screen.helper).end()
        assertEquals(Color.WHITE, iconColor(screen.toolbar.navigationIcon!!))
        screen.helper.restore()
    }

    @Test
    fun `collapsing during a color fade keeps its current color and restores the original icon`() {
        val screen = profile()
        val originalColor = iconColor(screen.toolbar.navigationIcon!!)
        screen.helper.revealAfterLayout {}
        screen.root.viewTreeObserver.dispatchOnPreDraw()
        screen.banner.setImageDrawable(ColorDrawable(Color.WHITE))
        layout(screen.root)
        screen.root.viewTreeObserver.dispatchOnPreDraw()
        val animator = glyphAnimator(screen.helper)
        animator.currentPlayTime = animator.duration / 2
        val middle = iconColor(screen.toolbar.navigationIcon!!)
        screen.helper.onOffsetChanged(AppBarLayout(screen.root.context), -1000)
        assertEquals(middle, iconColor(screen.toolbar.navigationIcon!!))
        glyphAnimator(screen.helper).end()
        assertEquals(originalColor, iconColor(screen.toolbar.navigationIcon!!))
        screen.helper.restore()
    }

    @Test
    fun `restoring during a collapse fade restores the original icon immediately`() {
        val screen = profile()
        val original = screen.toolbar.navigationIcon
        screen.helper.revealAfterLayout {}
        screen.root.viewTreeObserver.dispatchOnPreDraw()
        screen.helper.onOffsetChanged(AppBarLayout(screen.root.context), -1000)
        screen.helper.restore()
        assertSame(original, screen.toolbar.navigationIcon)
    }

    @Test
    fun `clearing a banner returns the controls to their theme colors`() {
        val screen = profile()
        val original = screen.toolbar.navigationIcon
        screen.banner.setImageDrawable(ColorDrawable(Color.WHITE))
        layout(screen.root)
        screen.helper.revealAfterLayout {}
        screen.root.viewTreeObserver.dispatchOnPreDraw()
        screen.banner.setImageDrawable(null)
        layout(screen.root)
        screen.root.viewTreeObserver.dispatchOnPreDraw()
        assertFalse(WindowInsetsControllerCompat(screen.activity.window, screen.activity.window.decorView).isAppearanceLightStatusBars)
        glyphAnimator(screen.helper).end()
        assertSame(original, screen.toolbar.navigationIcon)
        screen.helper.restore()
    }

    @Test
    fun `uncopyable icons recover their original untinted color on restore`() {
        val screen = profile()
        val icon = object : ColorDrawable(Color.WHITE) {
            override fun getConstantState(): Drawable.ConstantState? = null
        }
        screen.toolbar.clearNavigationIconTint()
        screen.toolbar.navigationIcon = icon
        screen.banner.setImageDrawable(ColorDrawable(Color.WHITE))
        layout(screen.root)
        screen.helper.revealAfterLayout {}
        screen.root.viewTreeObserver.dispatchOnPreDraw()
        assertEquals(BannerContrast.DARK, iconColor(screen.toolbar.navigationIcon!!))
        screen.helper.restore()
        assertNull(screen.toolbar.navigationIconTint)
        assertSame(icon, screen.toolbar.navigationIcon)
        assertEquals(Color.WHITE, iconColor(screen.toolbar.navigationIcon!!))
    }

    private fun glyphAnimator(helper: ProfileBannerUiHelper): ValueAnimator {
        return ProfileBannerUiHelper::class.java.getDeclaredField("glyphAnimator").apply { isAccessible = true }.get(helper) as ValueAnimator
    }

    private fun iconColor(drawable: Drawable): Int {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 32, 32)
        drawable.draw(Canvas(bitmap))
        return bitmap.getPixel(16, 16).also { bitmap.recycle() }
    }

    private data class Profile(
            val activity: Activity,
            val root: FrameLayout,
            val banner: ImageView,
            val helper: ProfileBannerUiHelper,
            val request: Request,
            val toolbar: MaterialToolbar,
    )

    private fun profile(): Profile {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val context = ContextThemeWrapper(activity, com.google.android.material.R.style.Theme_MaterialComponents)
        val root = FrameLayout(context)
        val collapsing = CollapsingToolbarLayout(context)
        val banner = ImageView(context).apply { setImageDrawable(ColorDrawable(Color.BLACK)) }
        val toolbar = MaterialToolbar(context).apply { navigationIcon = ColorDrawable(Color.WHITE) }
        root.addView(collapsing, FrameLayout.LayoutParams(1000, 600))
        collapsing.addView(banner, CollapsingToolbarLayout.LayoutParams(1000, 300))
        collapsing.addView(toolbar, CollapsingToolbarLayout.LayoutParams(1000, 56))
        activity.setContentView(root)
        val host = mockk<VectorBaseActivity<*>>(relaxed = true)
        every { host.window } returns activity.window
        every { host.systemBarsTopInset } returns 24
        val request = mockk<Request>()
        every { request.isRunning } returns true
        every { request.isComplete } returns false
        every { request.isCleared } returns false
        DrawableImageViewTarget(banner).request = request
        val helper = ProfileBannerUiHelper(host, toolbar, collapsing, banner)
        helper.update(true)
        layout(root)
        assertEquals(2, collapsing.childCount)
        return Profile(activity, root, banner, helper, request, toolbar)
    }

    private fun layout(view: View) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1000, 600)
    }

    private fun imageView(
            drawable: android.graphics.drawable.Drawable,
            scaleType: ImageView.ScaleType = ImageView.ScaleType.FIT_CENTER,
    ): ImageView {
        return ImageView(context).apply {
            this.scaleType = scaleType
            setImageDrawable(drawable)
            measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(100, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(100, android.view.View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 100, 100)
        }
    }
}
