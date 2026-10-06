/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import android.content.Context
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.MenuRes
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.text.toSpannable
import androidx.core.view.ViewCompat
import androidx.core.view.forEach
import im.vector.app.core.utils.colorizeMatchingText
import im.vector.app.features.themes.ThemeUtils

fun MenuItem.setTextColor(@ColorInt color: Int) {
    val currentTitle = title.orEmpty().toString()
    title = currentTitle
            .toSpannable()
            .colorizeMatchingText(currentTitle, color)
}

/**
 * A popup menu of [menuRes] with its icons shown, on the theme's surface color like the toolbar overflow
 * (the theme's popupMenuStyle drops that background).
 */
fun iconPopupMenu(context: Context, anchor: View, @MenuRes menuRes: Int): PopupMenu {
    val popup = PopupMenu(context, anchor, Gravity.NO_GRAVITY, 0, com.google.android.material.R.style.Widget_MaterialComponents_PopupMenu)
    popup.inflate(menuRes)
    // The icons are flat colors of their own, which the popup's theme may well not read against.
    val tint = ThemeUtils.getColorFromContextTheme(context, im.vector.lib.ui.styles.R.attr.vctr_content_primary)
    // AppCompat's row hardcodes the icon 8dp from the edge; inset it as much again on that side.
    val inset = (ICON_EXTRA_START_INSET_DP * context.resources.displayMetrics.density).toInt()
    val rtl = ViewCompat.getLayoutDirection(anchor) == ViewCompat.LAYOUT_DIRECTION_RTL
    popup.menu.forEach { item ->
        val icon = item.icon?.mutate() ?: return@forEach
        DrawableCompat.setTint(icon, tint)
        item.icon = InsetDrawable(icon, if (rtl) 0 else inset, 0, if (rtl) inset else 0, 0)
    }
    // A popup menu hides its icons until forced to, on every level appcompat covers.
    popup.setForceShowIcon(true)
    return popup
}

private const val ICON_EXTRA_START_INSET_DP = 8
