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
    fun getCurrentLogo(): AppLogo = AppLogo.fromStorageValue(
            preferences.getString(AppLogo.SETTINGS_APP_LOGO_KEY, AppLogo.DEFAULT.storageValue)
    )

    fun getCurrentColor(): AppIconColor = AppIconColor.fromStorageValue(
            preferences.getString(AppIconColor.SETTINGS_APP_ICON_COLOR_KEY, AppIconColor.DEFAULT.storageValue)
    )

    fun setCurrentLogo(logo: AppLogo) {
        preferences.edit { putString(AppLogo.SETTINGS_APP_LOGO_KEY, logo.storageValue) }
        applyLauncherAlias(logo, getCurrentColor())
    }

    fun setCurrentColor(color: AppIconColor) {
        preferences.edit { putString(AppIconColor.SETTINGS_APP_ICON_COLOR_KEY, color.storageValue) }
        applyLauncherAlias(getCurrentLogo(), color)
    }

    private fun applyLauncherAlias(logo: AppLogo, color: AppIconColor) {
        val pm = context.packageManager
        val pkg = context.packageName
        val selected = aliasClassName(logo, color)
        // Enable the chosen alias first so a launcher entry always exists, then disable the others.
        pm.setComponentEnabledSetting(
                ComponentName(pkg, selected),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
        )
        val others = AppLogo.values().flatMap { otherLogo -> AppIconColor.values().map { otherLogo to it } }
        others.forEach { (otherLogo, otherColor) ->
            val alias = aliasClassName(otherLogo, otherColor)
            if (alias == selected) return@forEach
            val component = ComponentName(pkg, alias)
            // Every combination has an alias, so only touch the ones that are actually on:
            // setComponentEnabledSetting is a system-settings write each time.
            val enabled = when (pm.getComponentEnabledSetting(component)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
                // Never switched away from the manifest value, where only the default pair is enabled.
                else -> otherLogo == AppLogo.DEFAULT && otherColor == AppIconColor.DEFAULT
            }
            if (enabled) {
                pm.setComponentEnabledSetting(
                        component,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                )
            }
        }
    }

    private fun aliasClassName(logo: AppLogo, color: AppIconColor): String {
        // Aliases are declared in the vector-app manifest against its manifest package im.vector.application.
        return if (logo == AppLogo.DEFAULT && color == AppIconColor.DEFAULT) {
            "im.vector.application.features.Alias"
        } else {
            "im.vector.application.features.Launcher${logo.storageValue.capitalizeFirst()}${color.storageValue.capitalizeFirst()}"
        }
    }

    private fun String.capitalizeFirst() = replaceFirstChar { it.uppercaseChar() }
}
