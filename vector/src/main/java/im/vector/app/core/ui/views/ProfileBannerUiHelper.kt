/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.features.themes.ThemeUtils

/**
 * Drives a profile screen while a banner is drawn behind the transparent toolbar: the page extends
 * behind the (transparent) status bar so the banner reaches the top screen edge, with one black
 * fade over it and light system status icons. The pinned toolbar is pushed below the status bar.
 * The back arrow keeps its theme color except on light themes, where it would vanish against the
 * fade and is forced white instead; menu icons are accent-colored and stay legible as they are.
 *
 * On Android versions where content cannot extend behind the status bar the inset is 0 and all of
 * this naturally no-ops: the banner just starts below the system-drawn bar.
 */
class ProfileBannerUiHelper(
        private val activity: VectorBaseActivity<*>,
        private val toolbar: MaterialToolbar,
        private val collapsingToolbarLayout: CollapsingToolbarLayout,
        private val scrimView: View,
) {

    private val insetsController = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
    private val originalLightStatusBars = insetsController.isAppearanceLightStatusBars
    private val isLightTheme = ThemeUtils.isLightTheme(activity)
    private val originalScrimHeight = scrimView.layoutParams.height
    private val originalScrimTrigger = collapsingToolbarLayout.scrimVisibleHeightTrigger

    private var overBanner = false

    init {
        scrimView.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.BLACK, Color.TRANSPARENT)
        )
    }

    fun update(bannerVisible: Boolean, collapsed: Boolean) {
        overBanner = bannerVisible && !collapsed
        activity.setDrawUnderStatusBar(bannerVisible)
        val topInset = if (bannerVisible) activity.systemBarsTopInset else 0
        (toolbar.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            if (params.topMargin != topInset) {
                params.topMargin = topInset
                toolbar.layoutParams = params
            }
        }
        // The fade covers the status-bar strip plus the toolbar area below it
        val scrimHeight = originalScrimHeight + topInset
        if (scrimView.layoutParams.height != scrimHeight) {
            scrimView.layoutParams = scrimView.layoutParams.apply { height = scrimHeight }
        }
        // The toolbar's inset margin raises the collapsed height; without raising the trigger too,
        // the contentScrim never shows and banner remnants stay visible behind the collapsed toolbar.
        collapsingToolbarLayout.scrimVisibleHeightTrigger = originalScrimTrigger + topInset
        insetsController.isAppearanceLightStatusBars = if (overBanner) false else originalLightStatusBars
        applyIconTint()
    }

    fun restore() {
        overBanner = false
        activity.setDrawUnderStatusBar(false)
        collapsingToolbarLayout.scrimVisibleHeightTrigger = originalScrimTrigger
        insetsController.isAppearanceLightStatusBars = originalLightStatusBars
        applyIconTint()
    }

    private fun applyIconTint() {
        // The dark themes' grey back arrow reads fine over the black fade; only light themes need
        // forcing it to white there.
        val navColor = if (overBanner && isLightTheme) {
            Color.WHITE
        } else {
            ThemeUtils.getColor(toolbar.context, im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
        }
        toolbar.setNavigationIconTint(navColor)
    }
}
