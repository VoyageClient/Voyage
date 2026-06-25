/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import android.graphics.Color

/**
 * Resolves text colours for HTML messages from `data-mx-color` / `data-mx-bg-color` / `color` and from a
 * `style` attribute. For `style`, only `color` and `background-color` are read — arbitrary CSS is never
 * interpreted (it would be an injection risk).
 */
object HtmlColorParser {

    /** Foreground colour for a tag, or null to inherit the default text colour. */
    fun foregroundColor(attributes: Map<String, String>): Int? =
            parseColor(attributes["data-mx-color"])
                    ?: parseColor(attributes["color"])
                    ?: styleColor(attributes["style"], "color")

    /** Background colour for a tag, or null for none. */
    fun backgroundColor(attributes: Map<String, String>): Int? =
            parseColor(attributes["data-mx-bg-color"])
                    ?: styleColor(attributes["style"], "background-color")

    /** Reads a single colour [property] (`color` / `background-color`) out of a CSS `style` value. */
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

    /** Parses `#rgb`/`#rrggbb`/`#aarrggbb` or a named colour; null when it can't be resolved. */
    fun parseColor(value: String?): Int? {
        val color = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { return Color.parseColor(color) }
        // Color.parseColor knows only a subset of named colours on API 19; cover the rest of the basic palette.
        return when (color.lowercase()) {
            "white" -> Color.WHITE
            "yellow" -> Color.YELLOW
            "fuchsia" -> Color.parseColor("#FF00FF")
            "red" -> Color.RED
            "silver" -> Color.parseColor("#C0C0C0")
            "gray", "grey" -> Color.GRAY
            "olive" -> Color.parseColor("#808000")
            "purple" -> Color.parseColor("#800080")
            "maroon" -> Color.parseColor("#800000")
            "aqua" -> Color.parseColor("#00FFFF")
            "lime" -> Color.parseColor("#00FF00")
            "teal" -> Color.parseColor("#008080")
            "green" -> Color.GREEN
            "blue" -> Color.BLUE
            "orange" -> Color.parseColor("#FFA500")
            "navy" -> Color.parseColor("#000080")
            "black" -> Color.BLACK
            else -> null
        }
    }
}
