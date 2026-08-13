/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import android.content.Context
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.preference.PreferenceManager
import im.vector.app.R
import im.vector.app.features.themes.ThemeUtils

/**
 * Background colour of the launcher icon. The values mirror the accent-colour picker entries
 * (`sc_accent_color_light_values`), which the preference reuses for its swatches.
 */
enum class AppIconColor(val storageValue: String) {
    RED("red"),
    VIBECODER("vibecoder"),
    ORANGE("orange"),
    AMBER("amber"),
    YELLOW("yellow"),
    GOLD("gold"),
    LIME("lime"),
    GREEN("green"),
    GREENDARK("greendark"),
    ELEMENT("element"),
    TEAL("teal"),
    TURQUOISE("turquoise"),
    CYAN("cyan"),
    BLUELIGHT("bluelight"),
    BLUE("blue"),
    DENIM("denim"),
    INDIGO("indigo"),
    PURPLE("purple"),
    CARNATION("carnation"),
    PINK("pink"),
    LAVA("lava"),
    PEACH("peach"),
    SLATE("slate"),
    ROSE("rose"),
    MAGENTA("magenta"),
    VIOLET("violet"),
    LAVENDER("lavender"),
    PERIWINKLE("periwinkle"),
    WHITE("white"),
    BLACK("black");

    /**
     * The launcher background this colour paints, taken from the picker's swatches. White and black
     * are drawn as the theme's contrasting colour instead, so the mark stays visible either way.
     */
    @ColorInt
    fun asMarkColor(context: Context): Int {
        if (this == WHITE || this == BLACK) {
            return if (ThemeUtils.isLightTheme(context)) Color.BLACK else Color.WHITE
        }
        val values = context.resources.getStringArray(R.array.sc_accent_color_light_values)
        val previews = context.resources.getStringArray(R.array.sc_accent_color_previews)
        val index = values.indexOf(storageValue)
        return runCatching { Color.parseColor(previews[index]) }.getOrDefault(Color.WHITE)
    }

    companion object {
        const val SETTINGS_APP_ICON_COLOR_KEY = "SETTINGS_APP_ICON_COLOR_KEY"

        val DEFAULT = BLACK

        fun fromStorageValue(value: String?): AppIconColor = values().firstOrNull { it.storageValue == value } ?: DEFAULT

        fun current(context: Context): AppIconColor = fromStorageValue(
                PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
                        .getString(SETTINGS_APP_ICON_COLOR_KEY, DEFAULT.storageValue)
        )
    }
}
