/*
 * Copyright 2018-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.themes

import androidx.annotation.StyleRes
import im.vector.lib.ui.styles.R

/**
 * Class to manage Activity other possible themes.
 * Light is no longer the implicit manifest default — every theme variant is listed explicitly.
 */
sealed class ActivityOtherThemes(
        @StyleRes val light: Int,
        @StyleRes val dark: Int,
        @StyleRes val black: Int,
        @StyleRes val sc_light: Int,
        @StyleRes val sc: Int,
        @StyleRes val sc_dark: Int,
        @StyleRes val sc_colored: Int,
        @StyleRes val sc_dark_colored: Int
) {

    object Default : ActivityOtherThemes(
            R.style.Theme_Vector_Light,
            R.style.Theme_Vector_Dark,
            R.style.Theme_Vector_Black,
            R.style.AppTheme_SC_Light,
            R.style.AppTheme_SC,
            R.style.AppTheme_SC_Dark,
            R.style.AppTheme_SC_Colored,
            R.style.AppTheme_SC_Dark_Colored
    )

    object Launcher : ActivityOtherThemes(
            R.style.Theme_Vector_Launcher,
            R.style.Theme_Vector_Launcher,
            R.style.Theme_Vector_Launcher,
            R.style.AppTheme_Launcher_SC,
            R.style.AppTheme_Launcher_SC,
            R.style.AppTheme_Launcher_SC,
            R.style.AppTheme_Launcher_SC,
            R.style.AppTheme_Launcher_SC
    )

    // Theme_Vector_Black rather than Theme_Vector_Black_AttachmentsPreview: the latter has no accent
    // variants, so ?colorAccent and friends resolved to the default green. The transparent system bars
    // it carried are set from code instead, see VectorBaseActivity.makeSystemBarsTransparent().
    object AttachmentsPreview : ActivityOtherThemes(
            R.style.Theme_Vector_Black,
            R.style.Theme_Vector_Black,
            R.style.Theme_Vector_Black,
            R.style.AppTheme_AttachmentsPreview_SC,
            R.style.AppTheme_AttachmentsPreview_SC,
            R.style.AppTheme_AttachmentsPreview_SC,
            R.style.AppTheme_AttachmentsPreview_SC,
            R.style.AppTheme_AttachmentsPreview_SC
    )

    // Window translucency comes from the manifest theme; this RUNTIME theme only styles views inflated
    // afterwards (playbar, loading spinners), so it must be accent-aware rather than the transparent theme.
    object VectorAttachmentsPreview : ActivityOtherThemes(
            R.style.Theme_Vector_Black,
            R.style.Theme_Vector_Black,
            R.style.Theme_Vector_Black,
            R.style.AppTheme_Transparent_SC,
            R.style.AppTheme_Transparent_SC,
            R.style.AppTheme_Transparent_SC,
            R.style.AppTheme_Transparent_SC,
            R.style.AppTheme_Transparent_SC
    )
}
