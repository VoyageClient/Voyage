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
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.SystemClock
import android.view.Menu
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.appcompat.widget.ActionMenuView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.MenuItemCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.get
import androidx.core.view.size
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.features.media.MediaPlaceholderDrawable
import im.vector.app.features.themes.ThemeUtils

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
    private var navigationShadow = true
    private var menuShadow = true
    private var darkStatusBar = false
    private val pageColor = ThemeUtils.getColor(toolbar.context, android.R.attr.colorBackground)
    private val sampler = BannerImageSampler(pageColor)
    private val toolbarColor = StableBannerColor()
    private val statusColor = StableBannerColor()
    private var profileRevealed = false
    private var pendingReveal: (() -> Unit)? = null
    private var sampledBanner: Drawable? = null
    private var sampledGeometry: SampleGeometry? = null
    private val sampledMatrix = Matrix()
    private var dirty = true
    private var lastCheckAt = 0L
    private val location = IntArray(2)

    private var originalNavigationIcon: Drawable? = null
    private var originalNavigationTint: Int? = null
    private val originalMenuIcons = HashMap<Int, Drawable?>()
    private val originalMenuTints = HashMap<Int, ColorStateList?>()

    private data class GlyphStyle(val dark: Boolean, val navigationShadow: Boolean, val menuShadow: Boolean)

    private var appliedGlyphs: GlyphStyle? = null

    private val themeGlyphColor = ThemeUtils.getColor(toolbar.context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
    private val activeGlyphs = mutableListOf<BannerGlyphDrawable>()

    private var glyphAnimator: ValueAnimator? = null

    private var sampleScheduled = false
    private val sampleAgain = Runnable {
        sampleScheduled = false
        bannerView.invalidate()
    }

    private val bannerWatcher = ViewTreeObserver.OnPreDrawListener {
        if (bannerVisible) {
            updateToolbarInsets()
            if (!collapsed && bannerView.isShown) analyzeBanner()
        }
        val reveal = pendingReveal
        if (reveal != null && readyToReveal()) {
            pendingReveal = null
            profileRevealed = true
            applyGlyphs()
            reveal()
        }
        true
    }

    init {
        bannerView.viewTreeObserver.addOnPreDrawListener(bannerWatcher)
    }

    override fun onOffsetChanged(appBarLayout: AppBarLayout, verticalOffset: Int) {
        dirty = true
        val nowCollapsed = collapsingToolbarLayout.height + verticalOffset < collapsingToolbarLayout.scrimVisibleHeightTrigger
        if (nowCollapsed == collapsed) return
        collapsed = nowCollapsed
        applyGlyphs(animate = true)
    }

    fun update(bannerVisible: Boolean) {
        dirty = dirty || this.bannerVisible != bannerVisible
        this.bannerVisible = bannerVisible
        activity.setDrawUnderStatusBar(bannerVisible)
        updateToolbarInsets()
        applyGlyphs()
    }

    private fun updateToolbarInsets() {
        val topInset = if (bannerVisible) activity.systemBarsTopInset else 0
        if (toolbar.paddingTop != topInset) {
            toolbar.setPadding(toolbar.paddingLeft, topInset, toolbar.paddingRight, toolbar.paddingBottom)
            toolbar.layoutParams = toolbar.layoutParams.apply { height = originalToolbarHeight + topInset }
        }
        // The taller toolbar raises the collapsed height; without raising the trigger too, the
        // contentScrim never shows and banner remnants stay visible behind the collapsed toolbar.
        collapsingToolbarLayout.scrimVisibleHeightTrigger = originalScrimTrigger + topInset
    }

    fun onMenuCreated(menu: Menu) {
        originalMenuIcons.clear()
        originalMenuTints.clear()
        for (i in 0 until menu.size) {
            val item = menu[i]
            originalMenuIcons[item.itemId] = item.icon
            originalMenuTints[item.itemId] = MenuItemCompat.getIconTintList(item)
        }
        appliedGlyphs = null
        dirty = true
        applyGlyphs()
    }

    fun revealAfterLayout(reveal: () -> Unit) {
        pendingReveal = reveal
        toolbar.invalidate()
    }

    private fun readyToReveal(): Boolean {
        if (!bannerVisible || collapsed) return true
        return !toolbar.isLayoutRequested && !collapsingToolbarLayout.isLayoutRequested
    }

    fun restore() {
        bannerView.viewTreeObserver.removeOnPreDrawListener(bannerWatcher)
        glyphAnimator?.cancel()
        glyphAnimator = null
        bannerVisible = false
        activity.setDrawUnderStatusBar(false)
        collapsingToolbarLayout.scrimVisibleHeightTrigger = originalScrimTrigger
        insetsController.isAppearanceLightStatusBars = originalLightStatusBars
        restoreOriginalIcons()
        appliedGlyphs = null
        pendingReveal = null
        bannerView.removeCallbacks(sampleAgain)
        sampleScheduled = false
        sampler.release()
    }

    private fun applyGlyphs(animate: Boolean = false) {
        val overBanner = profileRevealed && bannerVisible && !collapsed && sampledBanner != null && sampledBanner === bannerView.drawable
        val state = if (overBanner) GlyphStyle(darkGlyphs, navigationShadow, menuShadow) else null
        insetsController.isAppearanceLightStatusBars = if (overBanner) darkStatusBar else originalLightStatusBars
        if (state == appliedGlyphs) return
        appliedGlyphs = state
        if (originalNavigationIcon == null) {
            originalNavigationIcon = toolbar.navigationIcon
            originalNavigationTint = toolbar.navigationIconTint
        }
        glyphAnimator?.cancel()
        glyphAnimator = null
        if (overBanner) {
            installGlyphs(if (darkGlyphs) DARK_GLYPH else LIGHT_GLYPH, animate)
            setGlyphProgress(0f)
            if (animate) animateGlyphs() else setGlyphProgress(1f)
        } else if (animate && activeGlyphs.isNotEmpty()) {
            activeGlyphs.forEach { it.transitionTo(GlyphAppearance(themeGlyphColor, 0f)) }
            setGlyphProgress(0f)
            animateGlyphs { restoreOriginalIcons() }
        } else {
            restoreOriginalIcons()
        }
    }

    private fun installGlyphs(@ColorInt color: Int, animate: Boolean) {
        activeGlyphs.clear()
        originalNavigationIcon?.let { icon ->
            val from = if (animate) (toolbar.navigationIcon as? BannerGlyphDrawable)?.appearance ?: GlyphAppearance(themeGlyphColor, 0f) else null
            val glyph = bannerGlyph(icon, color, navigationShadow, from)
            toolbar.navigationIcon = glyph ?: icon
            if (glyph == null) toolbar.setNavigationIconTint(color)
        }
        val menu = toolbar.menu
        for (i in 0 until menu.size) {
            val item = menu[i]
            val icon = originalMenuIcons[item.itemId] ?: continue
            val from = if (animate) (item.icon as? BannerGlyphDrawable)?.appearance ?: GlyphAppearance(themeGlyphColor, 0f) else null
            val glyph = bannerGlyph(icon, color, menuShadow, from)
            // The glyph carries its own color; a tint would be applied over it on older versions
            MenuItemCompat.setIconTintList(item, if (glyph != null) null else ColorStateList.valueOf(color))
            item.icon = glyph ?: icon
        }
    }

    private fun restoreOriginalIcons() {
        activeGlyphs.clear()
        originalNavigationIcon?.let { icon ->
            val tint = originalNavigationTint
            if (tint == null) toolbar.clearNavigationIconTint() else toolbar.setNavigationIconTint(tint)
            toolbar.navigationIcon = icon
        }
        val menu = toolbar.menu
        for (i in 0 until menu.size) {
            val item = menu[i]
            val icon = originalMenuIcons[item.itemId] ?: continue
            MenuItemCompat.setIconTintList(item, originalMenuTints[item.itemId])
            item.icon = icon
        }
    }

    private fun animateGlyphs(onEnd: (() -> Unit)? = null) {
        var canceled = false
        glyphAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
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
        activeGlyphs.forEach { it.setProgress(progress) }
    }

    private data class GlyphAppearance(@ColorInt val color: Int, val shadow: Float)

    // Copy inside AppCompat's tint wrapper so the collapsed toolbar keeps its original drawable.
    private fun bannerGlyph(icon: Drawable, @ColorInt color: Int, shadow: Boolean, from: GlyphAppearance?): Drawable? {
        val glyph = icon.freshCopy() ?: return null
        val target = GlyphAppearance(color, if (shadow) 1f else 0f)
        val initial = from ?: target
        val shadowSource = if (shadow || initial.shadow > 0f) icon.freshCopy() else null
        return BannerGlyphDrawable(glyph, shadowSource, initial, target, shadowBlur).also { activeGlyphs.add(it) }
    }

    private fun Drawable.freshCopy(): Drawable? = DrawableCompat.unwrap<Drawable>(this).constantState?.newDrawable()?.mutate()

    private data class SampleRegion(val rect: RectF, val navigation: Boolean = false)

    private data class SampleGeometry(
            val bannerX: Int,
            val bannerY: Int,
            val width: Int,
            val height: Int,
            val controls: List<SampleRegion>,
            val status: List<SampleRegion>,
    )

    private fun analyzeBanner() {
        val now = SystemClock.uptimeMillis()
        val drawable = bannerView.drawable
        if (drawable == null || drawable is MediaPlaceholderDrawable) {
            applyGlyphs(animate = profileRevealed)
            return
        }
        val imageChanged = drawable !== sampledBanner
        if (imageChanged) {
            dirty = true
        }
        if (profileRevealed && !imageChanged && now - lastCheckAt < SAMPLE_INTERVAL_MS) {
            scheduleSample()
            return
        }
        lastCheckAt = now
        val geometry = sampleGeometry()
        val geometryChanged = geometry != sampledGeometry || bannerView.imageMatrix != sampledMatrix
        val changingImage = drawable.isChanging()
        if (!dirty && !geometryChanged && !changingImage) return
        if ((imageChanged || geometryChanged || changingImage) && !sampler.render(bannerView)) return
        sampledBanner = drawable
        sampledGeometry = geometry
        sampledMatrix.set(bannerView.imageMatrix)
        dirty = false
        val controls = geometry.controls.map { it to sampler.sample(it.rect) }
        val status = geometry.status.map { it to sampler.sample(it.rect) }
        val canUseDark = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
        // Keep white controls on older devices, where the system status icons cannot turn dark.
        val navigationKnown = toolbar.navigationIcon == null || controls.any { it.first.navigation }
        darkGlyphs = toolbarColor.choose(if (canUseDark && navigationKnown) controls.map { it.second } else emptyList(), reset = imageChanged)
        darkStatusBar = statusColor.choose(if (canUseDark) status.map { it.second } else emptyList(), reset = imageChanged)
        navigationShadow = !darkGlyphs && (!navigationKnown || controls.isEmpty() || controls.any { it.first.navigation && it.second.needsShadow })
        menuShadow = !darkGlyphs && (controls.isEmpty() || controls.any { !it.first.navigation && it.second.needsShadow })
        applyGlyphs(animate = profileRevealed)
        if (changingImage) {
            scheduleSample()
        }
    }

    private fun scheduleSample() {
        if (sampleScheduled) return
        sampleScheduled = true
        bannerView.postDelayed(sampleAgain, SAMPLE_INTERVAL_MS)
    }

    private fun sampleGeometry(): SampleGeometry {
        bannerView.getLocationOnScreen(location)
        val bannerX = location[0]
        val bannerY = location[1]
        val controls = mutableListOf<SampleRegion>()
        for (index in 0 until toolbar.childCount) {
            val child = toolbar.getChildAt(index)
            if (!child.isShown) continue
            when (child) {
                is ImageView -> if (child.drawable === toolbar.navigationIcon) {
                    iconRegion(child, bannerX, bannerY, navigation = true)?.let { controls.add(it) }
                }
                is ActionMenuView -> for (itemIndex in 0 until child.childCount) {
                    val item = child.getChildAt(itemIndex)
                    if (item.isShown) iconRegion(item, bannerX, bannerY)?.let { controls.add(it) }
                }
            }
        }
        val status = mutableListOf<SampleRegion>()
        val inset = activity.systemBarsTopInset
        if (inset > 0) {
            activity.window.decorView.getLocationOnScreen(location)
            val left = location[0] - bannerX.toFloat()
            val top = location[1] - bannerY.toFloat()
            val width = activity.window.decorView.width.toFloat()
            status.add(SampleRegion(RectF(left, top, left + width * STATUS_SIDE_FRACTION, top + inset)))
            status.add(SampleRegion(RectF(left + width * (1f - STATUS_SIDE_FRACTION), top, left + width, top + inset)))
        }
        return SampleGeometry(bannerX, bannerY, bannerView.width, bannerView.height, controls, status)
    }

    private fun iconRegion(view: View, bannerX: Int, bannerY: Int, navigation: Boolean = false): SampleRegion? {
        val rect = when (view) {
            is ImageView -> {
                val drawable = view.drawable ?: return null
                RectF(drawable.bounds).also {
                    view.imageMatrix.mapRect(it)
                    it.offset(view.paddingLeft.toFloat(), view.paddingTop.toFloat())
                }
            }
            is TextView -> {
                val drawables = view.compoundDrawables
                val drawable = drawables[0] ?: drawables[2] ?: return null
                val width = drawable.bounds.width().toFloat()
                val height = drawable.bounds.height().toFloat()
                val left = if (drawables[0] != null) view.paddingLeft.toFloat() else view.width - view.paddingRight - width
                val top = view.paddingTop + (view.height - view.paddingTop - view.paddingBottom - height) / 2f
                RectF(left, top, left + width, top + height)
            }
            else -> return null
        }
        if (rect.isEmpty) return null
        val margin = toolbar.resources.displayMetrics.density * ICON_MARGIN_DP
        rect.inset(-margin, -margin)
        view.getLocationOnScreen(location)
        rect.offset(location[0] - bannerX.toFloat(), location[1] - bannerY.toFloat())
        return SampleRegion(rect, navigation)
    }

    private fun Drawable.isChanging(): Boolean = when (this) {
        is LayerDrawable -> (0 until numberOfLayers).any { getDrawable(it).isChanging() }
        is Animatable -> isRunning
        is MediaPlaceholderDrawable -> !isFailed()
        else -> false
    }

    private class BannerGlyphDrawable(
            private val glyph: Drawable,
            private val shadowSource: Drawable?,
            private var start: GlyphAppearance,
            private var target: GlyphAppearance,
            private val blurRadius: Float,
    ) : Drawable() {
        private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { this.color = SHADOW }
        private var shadowBitmap: Bitmap? = null
        private val shadowOffset = IntArray(2)
        private var drawAlpha = 255
        private var currentColor = start.color
        private var currentShadow = start.shadow
        val appearance get() = GlyphAppearance(currentColor, currentShadow)

        init {
            setProgress(0f)
        }

        fun transitionTo(target: GlyphAppearance) {
            start = appearance
            this.target = target
        }

        fun setProgress(progress: Float) {
            currentColor = ColorUtils.blendARGB(start.color, target.color, progress)
            currentShadow = start.shadow + (target.shadow - start.shadow) * progress
            glyph.colorFilter = PorterDuffColorFilter(currentColor, PorterDuff.Mode.SRC_IN)
            updateShadowAlpha()
            invalidateSelf()
        }

        private fun updateShadowAlpha() {
            shadowPaint.alpha = (Color.alpha(SHADOW) * currentShadow * drawAlpha / 255).toInt()
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
            val glyphBitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
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
        const val LIGHT_GLYPH = BannerContrast.WHITE
        const val DARK_GLYPH = BannerContrast.DARK

        const val SHADOW = 0xBF000000.toInt()
        const val SHADOW_BLUR_DP = 3f

        const val ICON_MARGIN_DP = 3f
        const val STATUS_SIDE_FRACTION = 0.45f
        const val SAMPLE_INTERVAL_MS = 200L
    }
}
