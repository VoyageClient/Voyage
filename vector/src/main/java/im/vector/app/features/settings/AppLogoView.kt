/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import im.vector.app.R

/**
 * Applies the currently selected [AppLogo] mark to any login/splash logo ImageView present in this
 * view hierarchy (both the login-header logo and the splash logo share this call), and the wordmark font.
 */
fun View.applySelectedAppLogo() {
    val logoRes = AppLogo.current(context).logoRes
    listOf(im.vector.lib.ui.styles.R.id.loginLogo, R.id.loginSplashLogo).forEach { id ->
        (findViewById<View?>(id) as? ImageView)?.setImageDrawable(AppCompatResources.getDrawable(context, logoRes))
    }
    (findViewById<View?>(R.id.loginSplashWordmark) as? TextView)?.let { wordmark ->
        wordmarkTypeface(context)?.let { wordmark.typeface = it }
    }
}

fun wordmarkTypeface(context: Context): Typeface? =
        runCatching { Typeface.createFromAsset(context.assets, "fonts/linux_libertine.otf") }.getOrNull()
