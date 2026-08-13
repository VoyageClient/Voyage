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
 * Selectable application logo. [logoRes] is the matching in-app mark shown on the login/splash
 * screens; the launcher icon itself comes from the activity-alias picked by [AppIcon].
 */
enum class AppLogo(
        val storageValue: String,
        @DrawableRes val logoRes: Int,
        @StringRes val titleRes: Int,
) {
    BURST("burst", R.drawable.app_logo_burst, CommonStrings.settings_app_logo_burst),
    SPARK("spark", R.drawable.app_logo_spark, CommonStrings.settings_app_logo_spark),
    ELEMENT("element", R.drawable.app_logo_element, CommonStrings.settings_app_logo_element);

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
