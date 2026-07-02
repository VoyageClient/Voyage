/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import im.vector.app.R
import im.vector.lib.strings.CommonStrings

/**
 * Selectable application logo. [aliasClassName] is the manifest activity-alias (declared in the
 * vector-app manifest against its manifest package im.vector.application) whose launcher icon this
 * logo maps to; [logoRes] is the matching in-app mark shown on the login/splash screens.
 */
enum class AppLogo(
        val storageValue: String,
        @DrawableRes val logoRes: Int,
        val aliasClassName: String,
        @StringRes val titleRes: Int,
) {
    BURST("burst", R.drawable.app_logo_burst, "im.vector.application.features.Alias", CommonStrings.settings_app_logo_burst),
    SPARK("spark", R.drawable.app_logo_spark, "im.vector.application.features.LauncherSpark", CommonStrings.settings_app_logo_spark),
    ELEMENT("element", R.drawable.app_logo_element, "im.vector.application.features.LauncherElement", CommonStrings.settings_app_logo_element);

    companion object {
        const val SETTINGS_APP_LOGO_KEY = "SETTINGS_APP_LOGO_KEY"

        val DEFAULT = BURST

        fun fromStorageValue(value: String?): AppLogo = values().firstOrNull { it.storageValue == value } ?: DEFAULT

        fun current(context: Context): AppLogo = fromStorageValue(
                PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
                        .getString(SETTINGS_APP_LOGO_KEY, DEFAULT.storageValue)
        )
    }
}
