/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import android.view.View
import android.widget.ImageView
import im.vector.app.R

/**
 * Applies the currently selected [AppLogo] mark to any login/splash logo ImageView present in this
 * view hierarchy (both the login-header logo and the splash logo share this call).
 */
fun View.applySelectedAppLogo() {
    val logoRes = AppLogo.current(context).logoRes
    (findViewById<View?>(im.vector.lib.ui.styles.R.id.loginLogo) as? ImageView)?.setImageResource(logoRes)
    (findViewById<View?>(R.id.loginSplashLogo) as? ImageView)?.setImageResource(logoRes)
}
