/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import android.os.Build
import androidx.annotation.ColorInt
import androidx.core.view.ViewCompat
import com.google.android.material.appbar.AppBarLayout

/**
 * Paints the bar as a flat translucent scrim over the content behind it.
 *
 * The colour has to be set here rather than in the layout: AppBarLayout turns a background declared
 * in XML (or in the theme's appBarLayoutStyle) into a Material surface drawable, which never paints
 * a translucent colour as given. The elevation goes too — its shadow is inset from the screen edges,
 * so it reads as a second, narrower dim on top of this one.
 */
fun AppBarLayout.flattenAsScrim(@ColorInt color: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        stateListAnimator = null
    }
    ViewCompat.setElevation(this, 0f)
    setBackgroundColor(color)
}
