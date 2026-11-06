/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.Menu
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.MenuItemCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.features.themes.ThemeUtils
import kotlin.math.max
import kotlin.math.min

/**
 * Drives a profile screen while a banner is drawn behind the transparent toolbar: the page extends
 * behind the (transparent) status bar so the banner reaches the top screen edge. The toolbar is padded
 * below the status bar rather than pushed down, so once collapsed its own color also fills that strip.
 *
 * Over the banner, the back arrow, the menu icons and the status bar icons all take one color, white
 * or near-black, whichever reads better on the banner's top strip, so no scrim has to cover the image.
 */
class ProfileBannerUiHelper(
        private val activity: VectorBaseActivity<*>,
        private val toolbar: MaterialToolbar,
        private val collapsingToolbarLayout: CollapsingToolbarLayout,
        private val bannerView: ImageView,
) : AppBarLayout.OnOffsetChangedListener {

    private val insetsController = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
    private val originalLightStatusBars = insetsController.isAppearanceLightStatusBars
    private val originalScrimTrigger = collapsingToolbarLayout.scrimVisibleHeightTrigger
    private val originalToolbarHeight = toolbar.layoutParams.height
    private val shadowBlur = toolbar.resources.displayMetrics.density * SHADOW_BLUR_DP

    private var bannerVisible = false
    private var collapsed = false
    private var darkGlyphs = false
    private var navigationShadow = false
    private var menuShadow = false
    private var sampledBanner: Drawable? = null

    private var originalNavigationIcon: Drawable? = null
    private var originalNavigationTint: Int? = null
    private val originalMenuIcons = HashMap<Int, Drawable?>()
    private val originalMenuTints = HashMap<Int, ColorStateList?>()

    // What the toolbar currently shows, so a rebind that changes nothing leaves the icons alone
    private var appliedGlyphs: List<Any>? = null

    // The toolbar's usual icon color, which the glyphs fade to as the bar colors in on collapse
    private val themeGlyphColor = ThemeUtils.getColor(toolbar.context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
    private val activeGlyphs = mutableListOf<BannerGlyphDrawable>()

    // 0 = the banner color with its shadow, 1 = the theme color without
    private var glyphProgress = 0f
    private var glyphAnimator: ValueAnimator? = null

    private val bannerWatcher = ViewTreeObserver.OnPreDrawListener {
        // Glide sets the image asynchronously and may swap it (placeholder, crossfade), so follow it
        val drawable = bannerView.drawable
        if (drawable !== sampledBanner) {
            sampledBanner = drawable
            val pick = pickGlyphs(drawable)
            if (pick.dark != darkGlyphs || pick.navigationShadow != navigationShadow || pick.menuShadow != menuShadow) {
                darkGlyphs = pick.dark
                navigationShadow = pick.navigationShadow
                menuShadow = pick.menuShadow
                applyGlyphs()
            }
        }
        true
    }

    init {
        bannerView.viewTreeObserver.addOnPreDrawListener(bannerWatcher)
    }

    // Same test ProfileToolbarBackground uses, so the glyphs return to theme colors as the bar colors in
    override fun onOffsetChanged(appBarLayout: AppBarLayout, verticalOffset: Int) {
        val nowCollapsed = collapsingToolbarLayout.height + verticalOffset < collapsingToolbarLayout.scrimVisibleHeightTrigger
        if (nowCollapsed == collapsed) return
        collapsed = nowCollapsed
        applyGlyphs(animate = true)
    }

    fun update(bannerVisible: Boolean) {
        this.bannerVisible = bannerVisible
        activity.setDrawUnderStatusBar(bannerVisible)
        val topInset = if (bannerVisible) activity.systemBarsTopInset else 0
        if (toolbar.paddingTop != topInset) {
            toolbar.setPadding(toolbar.paddingLeft, topInset, toolbar.paddingRight, toolbar.paddingBottom)
            toolbar.layoutParams = toolbar.layoutParams.apply { height = originalToolbarHeight + topInset }
        }
        // The taller toolbar raises the collapsed height; without raising the trigger too, the
        // contentScrim never shows and banner remnants stay visible behind the collapsed toolbar.
        collapsingToolbarLayout.scrimVisibleHeightTrigger = originalScrimTrigger + topInset
        applyGlyphs()
    }

    /** Call once the menu is (re)created: its items come back with their XML icons and tint. */
    fun onMenuCreated(menu: Menu) {
        originalMenuIcons.clear()
        originalMenuTints.clear()
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            originalMenuIcons[item.itemId] = item.icon
            originalMenuTints[item.itemId] = MenuItemCompat.getIconTintList(item)
        }
        appliedGlyphs = null
        applyGlyphs()
    }

    fun restore() {
        bannerView.viewTreeObserver.removeOnPreDrawListener(bannerWatcher)
        glyphAnimator?.cancel()
        bannerVisible = false
        activity.setDrawUnderStatusBar(false)
        collapsingToolbarLayout.scrimVisibleHeightTrigger = originalScrimTrigger
        applyGlyphs()
    }

    /**
     * [animate]: a collapse or expand, which fades the glyphs between the banner color and the theme
     * color alongside the bar's own color; anything else (a new banner, a rebuilt menu) applies at once.
     */
    private fun applyGlyphs(animate: Boolean = false) {
        val overBanner = bannerVisible && !collapsed
        val state = if (overBanner) listOf(true, darkGlyphs, navigationShadow, menuShadow) else listOf(false)
        // Off the banner nothing was ever replaced, so a fresh screen without one keeps its icons untouched
        if (state == appliedGlyphs || (appliedGlyphs == null && !overBanner)) return
        appliedGlyphs = state
        insetsController.isAppearanceLightStatusBars = if (overBanner) darkGlyphs else originalLightStatusBars
        if (originalNavigationIcon == null) {
            originalNavigationIcon = toolbar.navigationIcon
            originalNavigationTint = toolbar.navigationIconTint
        }
        glyphAnimator?.cancel()
        glyphAnimator = null
        if (overBanner) {
            installGlyphs(if (darkGlyphs) DARK_GLYPH else LIGHT_GLYPH)
            if (animate) animateGlyphs(to = 0f) else setGlyphProgress(0f)
        } else if (animate && activeGlyphs.isNotEmpty()) {
            // Fade to the theme color first; the originals then swap in looking exactly the same
            animateGlyphs(to = 1f) { restoreOriginalIcons() }
        } else {
            restoreOriginalIcons()
        }
    }

    private fun installGlyphs(@ColorInt color: Int) {
        activeGlyphs.clear()
        originalNavigationIcon?.let { icon ->
            val glyph = bannerGlyph(icon, color, navigationShadow)
            toolbar.navigationIcon = glyph ?: icon
            if (glyph == null) toolbar.setNavigationIconTint(color)
        }
        val menu = toolbar.menu
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            val icon = originalMenuIcons[item.itemId] ?: continue
            val glyph = bannerGlyph(icon, color, menuShadow)
            // The glyph carries its own color; a tint would be applied over it on older versions
            MenuItemCompat.setIconTintList(item, if (glyph != null) null else ColorStateList.valueOf(color))
            item.icon = glyph ?: icon
        }
    }

    private fun restoreOriginalIcons() {
        activeGlyphs.clear()
        glyphProgress = 1f
        originalNavigationIcon?.let { icon ->
            toolbar.navigationIcon = icon
            originalNavigationTint?.let { toolbar.setNavigationIconTint(it) }
        }
        val menu = toolbar.menu
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            val icon = originalMenuIcons[item.itemId] ?: continue
            // Exactly the tint the item was inflated with
            MenuItemCompat.setIconTintList(item, originalMenuTints[item.itemId])
            item.icon = icon
        }
    }

    private fun animateGlyphs(to: Float, onEnd: (() -> Unit)? = null) {
        var canceled = false
        glyphAnimator = ValueAnimator.ofFloat(glyphProgress, to).apply {
            duration = collapsingToolbarLayout.scrimAnimationDuration
            addUpdateListener { setGlyphProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!canceled) onEnd?.invoke()
                }
            })
            start()
        }
    }

    private fun setGlyphProgress(progress: Float) {
        glyphProgress = progress
        activeGlyphs.forEach { it.setProgress(progress) }
    }

    /**
     * Built from fresh copies so the original icon, which comes back when the header collapses, is never
     * recolored. AppCompat hands out its icons wrapped for tinting, and a wrapper cannot be copied, so the
     * copy comes from the drawable inside; with none to copy the caller falls back to a plain tint.
     */
    private fun bannerGlyph(icon: Drawable, @ColorInt color: Int, shadow: Boolean): Drawable? {
        val glyph = icon.freshCopy() ?: return null
        return BannerGlyphDrawable(glyph, if (shadow) icon.freshCopy() else null, color, themeGlyphColor, shadowBlur)
                .also {
                    it.setProgress(glyphProgress)
                    activeGlyphs.add(it)
                }
    }

    private fun Drawable.freshCopy(): Drawable? = DrawableCompat.unwrap<Drawable>(this).constantState?.newDrawable()?.mutate()

    private class GlyphPick(val dark: Boolean, val navigationShadow: Boolean, val menuShadow: Boolean)

    /**
     * Picks one glyph color for both sides of the toolbar, and for each side whether it needs the shadow.
     *
     * Each side (the back button's area, the menu's area) votes for dark or white by which has the
     * better worst-case contrast over the banner behind it; dark only when both sides want it, as a
     * white glyph on a light patch stays legible with its shadow while a dark one on a dark patch is
     * lost. A white glyph gets the shadow where its own side's contrast is weak. Below API 23 the
     * status bar icons cannot turn dark, so everything stays white there.
     */
    private fun pickGlyphs(drawable: Drawable?): GlyphPick {
        val unknown = GlyphPick(dark = false, navigationShadow = true, menuShadow = true)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return unknown
        val bitmap = drawable?.firstBitmap() ?: return unknown
        val viewWidth = bannerView.width
        val viewHeight = bannerView.height
        if (viewWidth == 0 || viewHeight == 0 || bitmap.width == 0 || bitmap.height == 0) return unknown
        val readable = readableCopy(bitmap) ?: return unknown
        val buttonWidth = toolbar.resources.displayMetrics.density * BUTTON_AREA_DP
        val top = activity.systemBarsTopInset.toFloat()
        val bottom = min(viewHeight.toFloat(), top + originalToolbarHeight)
        val left = sample(readable, viewWidth, viewHeight, 0f, buttonWidth, top, bottom)
        val right = sample(readable, viewWidth, viewHeight, viewWidth - buttonWidth, viewWidth.toFloat(), top, bottom)
        val dark = left.wantsDark && right.wantsDark
        return GlyphPick(
                dark = dark,
                navigationShadow = !dark && left.worstWhite < SHADOW_BELOW_CONTRAST,
                menuShadow = !dark && right.worstWhite < SHADOW_BELOW_CONTRAST,
        )
    }

    private class SideContrast(val worstWhite: Double, val worstDark: Double) {
        val wantsDark get() = worstDark > worstWhite
    }

    /** Worst contrast of each glyph color over a grid of samples in a rect given in the banner view's coordinates. */
    private fun sample(bitmap: Bitmap, viewWidth: Int, viewHeight: Int, x0: Float, x1: Float, y0: Float, y1: Float): SideContrast {
        // The banner is center-cropped: map view coordinates back onto the bitmap
        val scale = max(viewWidth.toFloat() / bitmap.width, viewHeight.toFloat() / bitmap.height)
        val offsetX = (bitmap.width * scale - viewWidth) / 2f
        val offsetY = (bitmap.height * scale - viewHeight) / 2f
        var worstWhite = Double.MAX_VALUE
        var worstDark = Double.MAX_VALUE
        for (row in 0 until SAMPLE_ROWS) {
            for (col in 0 until SAMPLE_COLUMNS) {
                val viewX = x0 + (x1 - x0) * (col + 0.5f) / SAMPLE_COLUMNS
                val viewY = y0 + (y1 - y0) * (row + 0.5f) / SAMPLE_ROWS
                val x = ((viewX + offsetX) / scale).toInt().coerceIn(0, bitmap.width - 1)
                val y = ((viewY + offsetY) / scale).toInt().coerceIn(0, bitmap.height - 1)
                val pixel = ColorUtils.setAlphaComponent(bitmap.getPixel(x, y), 255)
                worstWhite = min(worstWhite, ColorUtils.calculateContrast(LIGHT_GLYPH, pixel))
                worstDark = min(worstDark, ColorUtils.calculateContrast(DARK_GLYPH, pixel))
            }
        }
        return SideContrast(worstWhite, worstDark)
    }

    private fun Drawable.firstBitmap(): Bitmap? = when (this) {
        is BitmapDrawable -> bitmap
        is GifDrawable -> firstFrame
        // A crossfade holds the outgoing and incoming images; the incoming one is the last layer
        is LayerDrawable -> if (numberOfLayers > 0) getDrawable(numberOfLayers - 1)?.firstBitmap() else null
        else -> null
    }

    // Hardware bitmaps (API 26+) cannot be read pixel by pixel, so sample a software copy of those
    private fun readableCopy(bitmap: Bitmap): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
            return runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        }
        return bitmap
    }

    /**
     * [glyph] in a single color, over an optional soft blurred halo of [shadowSource] as a shadow, blended by
     * [setProgress] from [bannerColor] with the shadow to [themeColor] without it.
     */
    private class BannerGlyphDrawable(
            private val glyph: Drawable,
            private val shadowSource: Drawable?,
            @ColorInt private val bannerColor: Int,
            @ColorInt private val themeColor: Int,
            private val blurRadius: Float,
    ) : Drawable() {
        private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { this.color = SHADOW }
        private var shadowBitmap: Bitmap? = null
        private val shadowOffset = IntArray(2)
        private var drawAlpha = 255
        private var progress = 0f

        init {
            setProgress(0f)
        }

        fun setProgress(progress: Float) {
            this.progress = progress
            glyph.colorFilter = PorterDuffColorFilter(ColorUtils.blendARGB(bannerColor, themeColor, progress), PorterDuff.Mode.SRC_IN)
            updateShadowAlpha()
            invalidateSelf()
        }

        private fun updateShadowAlpha() {
            shadowPaint.alpha = (Color.alpha(SHADOW) * (1f - progress) * drawAlpha / 255).toInt()
        }

        override fun getIntrinsicWidth() = glyph.intrinsicWidth
        override fun getIntrinsicHeight() = glyph.intrinsicHeight

        override fun onBoundsChange(bounds: Rect) {
            glyph.bounds = bounds
            shadowBitmap?.recycle()
            shadowBitmap = shadowSource?.let { buildShadow(it, bounds.width(), bounds.height()) }
        }

        // BlurMaskFilter is ignored by hardware-accelerated canvases before API 28, so the blur is baked
        // into an alpha mask once, in software, and that mask is what gets drawn (in the paint's color).
        private fun buildShadow(source: Drawable, width: Int, height: Int): Bitmap? {
            if (width <= 0 || height <= 0) return null
            val glyphBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            source.setBounds(0, 0, width, height)
            source.draw(Canvas(glyphBitmap))
            val blur = Paint().apply { maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL) }
            return glyphBitmap.extractAlpha(blur, shadowOffset).also { glyphBitmap.recycle() }
        }

        override fun draw(canvas: Canvas) {
            shadowBitmap?.let {
                canvas.drawBitmap(it, (bounds.left + shadowOffset[0]).toFloat(), (bounds.top + shadowOffset[1]).toFloat(), shadowPaint)
            }
            glyph.draw(canvas)
        }

        override fun setAlpha(alpha: Int) {
            glyph.alpha = alpha
            drawAlpha = alpha
            updateShadowAlpha()
        }

        // The glyph's color is fixed: a tint or filter from the toolbar must not repaint it
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private companion object {
        const val LIGHT_GLYPH = 0xFFFFFFFF.toInt()
        const val DARK_GLYPH = 0xFF15191E.toInt()

        // Centered on the glyph, so only what the blur spreads past its edges shows: strong, but soft
        const val SHADOW = 0xBF000000.toInt()
        const val SHADOW_BLUR_DP = 3f

        const val SAMPLE_COLUMNS = 6
        const val SAMPLE_ROWS = 6

        // The toolbar's 4dp end padding plus a 48dp button, on either side
        const val BUTTON_AREA_DP = 56

        // Below the 7:1 enhanced-contrast guideline, white glyphs get the shadow
        const val SHADOW_BELOW_CONTRAST = 7.0
    }
}
