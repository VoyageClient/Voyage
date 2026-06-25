/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.yalantis.ucrop.UCropActivity
import im.vector.app.features.themes.ActivityOtherThemes
import im.vector.app.features.themes.ThemeUtils

/**
 * uCrop's own activity has no window-inset handling and always runs under a static theme, so under Android
 * 15's enforced edge-to-edge its toolbar drew under the status bar (and controls under the navigation bar),
 * with the wrong (light) background and status-bar icon colours. It isn't a VectorBaseActivity, so it gets
 * none of our theming. This subclass applies the active Vector theme and pads the toolbar/controls by the
 * system-bar insets, keeping the bars transparent over uCrop's themed background.
 */
class VectorUCropActivity : UCropActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.setActivityTheme(this, ActivityOtherThemes.Default)
        super.onCreate(savedInstanceState)

        val isLight = ThemeUtils.isLightTheme(this)
        // The themed background shows behind the transparent system bars (and around the inset toolbar).
        window.setBackgroundDrawable(ColorDrawable(ThemeUtils.getColor(this, android.R.attr.colorBackground)))
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = isLight
            isAppearanceLightNavigationBars = isLight
        }

        val root = findViewById<View>(com.yalantis.ucrop.R.id.ucrop_photobox) ?: return
        val toolbar = findViewById<View>(com.yalantis.ucrop.R.id.toolbar)
        val controls = findViewById<View>(com.yalantis.ucrop.R.id.controls_wrapper)
        val toolbarTop = toolbar?.paddingTop ?: 0
        val controlsBottom = controls?.paddingBottom ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            toolbar?.updatePadding(top = toolbarTop + bars.top)
            controls?.updatePadding(bottom = controlsBottom + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
