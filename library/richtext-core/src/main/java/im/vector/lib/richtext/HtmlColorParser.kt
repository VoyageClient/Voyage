/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.richtext

import java.util.Locale

object HtmlColorParser {

    fun foregroundColor(attributes: Map<String, String>): Int? =
            parseColor(attributes["data-mx-color"])
                    ?: parseColor(attributes["color"])
                    ?: styleColor(attributes["style"], "color")

    fun backgroundColor(attributes: Map<String, String>): Int? =
            parseColor(attributes["data-mx-bg-color"])
                    ?: styleColor(attributes["style"], "background-color")

    private fun styleColor(style: String?, property: String): Int? {
        style ?: return null
        for (declaration in style.split(';')) {
            val separator = declaration.indexOf(':')
            if (separator <= 0) continue
            if (declaration.substring(0, separator).trim().equals(property, ignoreCase = true)) {
                return parseColor(declaration.substring(separator + 1))
            }
        }
        return null
    }

    /** `#rrggbb` / `#aarrggbb` or a named colour, as ARGB; null when it can't be resolved. */
    fun parseColor(value: String?): Int? {
        val color = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (color[0] == '#') {
            val digits = color.substring(1)
            val parsed = digits.toLongOrNull(16) ?: return null
            return when (digits.length) {
                6 -> (parsed or 0xFF000000L).toInt()
                8 -> parsed.toInt()
                else -> null
            }
        }
        return NAMED[color.lowercase(Locale.ROOT)]
    }

    // android.graphics.Color's name table plus the fallbacks the Android renderer adds.
    private val NAMED: Map<String, Int> = mapOf(
            "black" to 0xFF000000.toInt(),
            "darkgray" to 0xFF444444.toInt(),
            "gray" to 0xFF888888.toInt(),
            "lightgray" to 0xFFCCCCCC.toInt(),
            "white" to 0xFFFFFFFF.toInt(),
            "red" to 0xFFFF0000.toInt(),
            "green" to 0xFF00FF00.toInt(),
            "blue" to 0xFF0000FF.toInt(),
            "yellow" to 0xFFFFFF00.toInt(),
            "cyan" to 0xFF00FFFF.toInt(),
            "magenta" to 0xFFFF00FF.toInt(),
            "aqua" to 0xFF00FFFF.toInt(),
            "fuchsia" to 0xFFFF00FF.toInt(),
            "darkgrey" to 0xFF444444.toInt(),
            "grey" to 0xFF888888.toInt(),
            "lightgrey" to 0xFFCCCCCC.toInt(),
            "lime" to 0xFF00FF00.toInt(),
            "maroon" to 0xFF800000.toInt(),
            "navy" to 0xFF000080.toInt(),
            "olive" to 0xFF808000.toInt(),
            "purple" to 0xFF800080.toInt(),
            "silver" to 0xFFC0C0C0.toInt(),
            "teal" to 0xFF008080.toInt(),
            "orange" to 0xFFFFA500.toInt(),
    )
}
