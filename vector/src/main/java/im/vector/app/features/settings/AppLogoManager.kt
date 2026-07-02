/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.core.di.DefaultPreferences
import javax.inject.Inject

class AppLogoManager @Inject constructor(
        @ApplicationContext private val context: Context,
        @DefaultPreferences private val preferences: SharedPreferences,
) {
    fun getCurrent(): AppLogo = AppLogo.fromStorageValue(
            preferences.getString(AppLogo.SETTINGS_APP_LOGO_KEY, AppLogo.DEFAULT.storageValue)
    )

    fun setCurrent(logo: AppLogo) {
        preferences.edit { putString(AppLogo.SETTINGS_APP_LOGO_KEY, logo.storageValue) }
        applyLauncherAlias(logo)
    }

    private fun applyLauncherAlias(selected: AppLogo) {
        val pm = context.packageManager
        val pkg = context.packageName
        // Enable the chosen alias first so a launcher entry always exists, then disable the others.
        pm.setComponentEnabledSetting(
                ComponentName(pkg, selected.aliasClassName),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
        )
        AppLogo.values().filter { it != selected }.forEach {
            pm.setComponentEnabledSetting(
                    ComponentName(pkg, it.aliasClassName),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
            )
        }
    }
}
