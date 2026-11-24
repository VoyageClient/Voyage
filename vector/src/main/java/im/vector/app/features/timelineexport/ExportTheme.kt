/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import im.vector.app.features.themes.ThemeUtils
import im.vector.lib.ui.styles.R

object ExportTheme {
    fun light(theme: String) = theme == "light" || theme == "sc_light"

    fun style(theme: String, accent: String): Int = ThemeUtils.getAccentedThemeRes(when (theme) {
        "light" -> R.style.Theme_Vector_Light
        "black" -> R.style.Theme_Vector_Black
        "sc_light" -> R.style.AppTheme_SC_Light
        "sc_dark" -> R.style.AppTheme_SC_Dark
        "sc" -> R.style.AppTheme_SC
        "sc_colored" -> R.style.AppTheme_SC_Colored
        "sc_dark_colored" -> R.style.AppTheme_SC_Dark_Colored
        else -> R.style.Theme_Vector_Dark
    }, accent)
}
