/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.profile

/**
 * MSC4522 name color: a hex color per theme kind. Either member may be absent.
 */
data class ColorPreference(
        val onLight: String? = null,
        val onDark: String? = null,
) {
    fun isEmpty() = onLight == null && onDark == null

    fun forTheme(light: Boolean): String? = if (light) onLight ?: onDark else onDark ?: onLight

    fun toJson(): Map<String, String?> = mapOf(ON_LIGHT to onLight, ON_DARK to onDark)

    companion object {
        const val ON_LIGHT = "on_light"
        const val ON_DARK = "on_dark"

        private val HEX = Regex("^#([0-9A-Fa-f]{3}|[0-9A-Fa-f]{6})$")

        fun fromHex(hex: String) = ColorPreference(onLight = hex, onDark = hex)

        fun parse(raw: Any?): ColorPreference? {
            val map = raw as? Map<*, *> ?: return null
            return ColorPreference(normalizeHex(map[ON_LIGHT]), normalizeHex(map[ON_DARK])).takeIf { !it.isEmpty() }
        }

        /**
         * The color a set of profile (or member event) fields asks for: MSC4522 first, then the
         * read-only shapes other clients write — Sable's per-theme hex strings, its theme-less one,
         * then Commet's scheme object.
         */
        fun fromProfileFields(fields: Map<*, *>): ColorPreference? {
            parse(fields[ProfileKeys.COLOR_PREFERENCE])?.let { return it }
            parse(fields[ProfileKeys.COLOR_PREFERENCE_UNSTABLE])?.let { return it }
            val sableBoth = normalizeHex(fields[ProfileKeys.COLOR_SABLE])
            val sable = ColorPreference(
                    onLight = normalizeHex(fields[ProfileKeys.COLOR_SABLE_ON_LIGHT]) ?: sableBoth,
                    onDark = normalizeHex(fields[ProfileKeys.COLOR_SABLE_ON_DARK]) ?: sableBoth,
            )
            if (!sable.isEmpty()) return sable
            val commet = (fields[ProfileKeys.COLOR_COMMET] as? Map<*, *>)?.get("color")
            return normalizeHex(commet)?.let { fromHex(it) }
        }

        /** Accepts #RGB / #RRGGBB (tuwunel wraps the value in quotes) and returns uppercase #RRGGBB. */
        fun normalizeHex(raw: Any?): String? {
            val text = (raw as? String)?.replace("\"", "")?.replace("'", "")?.trim() ?: return null
            if (!HEX.matches(text)) return null
            val digits = text.substring(1)
            val expanded = if (digits.length == 3) digits.map { "$it$it" }.joinToString("") else digits
            return "#" + expanded.uppercase()
        }
    }
}
