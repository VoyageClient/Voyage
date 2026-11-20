/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.colorpicker

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * Which palette each picked color came from. Palettes share colors, and only the hex is saved with a
 * setting, so without this a color picked from a later palette reopens on the first one that has it.
 */
object ColorPaletteMemory {
    private const val KEY = "SETTINGS_COLOR_PICKER_PALETTE_MEMORY"
    private const val LAST_CUSTOM_KEY = "SETTINGS_COLOR_PICKER_LAST_CUSTOM"
    private const val MAX_ENTRIES = 256

    fun lastCustom(context: Context): String? =
            PreferenceManager.getDefaultSharedPreferences(context).getString(LAST_CUSTOM_KEY, null)

    fun rememberCustom(context: Context, hex: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit { putString(LAST_CUSTOM_KEY, hex.uppercase()) }
    }

    fun paletteFor(context: Context, hex: String): ColorPalette? {
        val name = read(context)[hex.uppercase()] ?: return null
        return ColorPalette.values().firstOrNull { it.name == name && it != ColorPalette.NONE }
    }

    fun remember(context: Context, hexes: Collection<String>, palette: ColorPalette) {
        val entries = read(context)
        hexes.forEach { hex ->
            val key = hex.uppercase()
            // Re-inserted so the most recent picks are the ones kept when the list is trimmed.
            entries.remove(key)
            entries[key] = palette.name
        }
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(KEY, entries.entries.joinToString(";") { "${it.key}=${it.value}" })
        }
    }

    private fun read(context: Context): LinkedHashMap<String, String> {
        val raw = PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, null).orEmpty()
        val entries = LinkedHashMap<String, String>()
        raw.split(';').forEach { pair ->
            val separator = pair.indexOf('=')
            if (separator > 0) entries[pair.substring(0, separator)] = pair.substring(separator + 1)
        }
        return entries
    }
}
