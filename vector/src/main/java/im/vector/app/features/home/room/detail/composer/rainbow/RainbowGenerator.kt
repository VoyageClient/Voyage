/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer.rainbow

import im.vector.app.core.utils.splitEmoji
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

class RainbowGenerator @Inject constructor() {

    sealed interface Palette {
        object Rainbow : Palette
        object Trans : Palette
        data class Gradient(val colors: List<Int>) : Palette
    }

    /**
     * Colors each visible character of [html] (a formatted body) while keeping its markup. Code and maths are
     * left as they are, and mention pills and emoticons keep their own look but still take a step of the sweep.
     */
    fun colorizeHtml(html: String, palette: Palette): String {
        val color = colorFunction(palette)
        val pieces = parse(html)
        val size = pieces.count { it.counted }
        var idx = 0
        return buildString {
            pieces.forEach { piece ->
                if (piece.colored) {
                    append("<font color=\"").append(color(idx, size)).append("\">").append(piece.raw).append("</font>")
                } else {
                    append(piece.raw)
                }
                if (piece.counted) idx++
            }
        }
    }

    private fun colorFunction(palette: Palette): (idx: Int, size: Int) -> String = when (palette) {
        Palette.Rainbow -> { idx, size -> hueColor(idx * 300.0 / size) }
        Palette.Trans -> { idx, size -> stopsColor(TRANS_STOPS, fraction(idx, size)) }
        is Palette.Gradient -> gradientColor(palette.colors)
    }

    private fun gradientColor(colors: List<Int>): (idx: Int, size: Int) -> String {
        require(colors.isNotEmpty())
        val stops = colors.map { intArrayOf((it shr 16) and 255, (it shr 8) and 255, it and 255) }
        return { idx, size -> stopsColor(stops, fraction(idx, size)) }
    }

    private fun fraction(idx: Int, size: Int) = if (size == 1) 0.0 else idx / (size - 1.0)

    /** A run of markup or a single character; [counted] pieces take a step of the sweep. */
    private class Piece(val raw: String, val counted: Boolean, val colored: Boolean)

    /** Markup kept whole up to its matching close tag, such as a code block or a mention pill. */
    private class Group(val tagName: String, val counted: Boolean) {
        val raw = StringBuilder()
        var depth = 1
    }

    private fun parse(html: String): List<Piece> {
        val pieces = mutableListOf<Piece>()
        var group: Group? = null
        var i = 0
        while (i < html.length) {
            if (html[i] == '<') {
                val end = html.indexOf('>', i).let { if (it < 0) html.lastIndex else it }
                val tag = html.substring(i, end + 1)
                i = end + 1
                val name = TAG_NAME.find(tag)?.groupValues?.get(1)?.lowercase()
                val closing = tag.startsWith("</")
                val selfClosing = tag.endsWith("/>")
                val current = group
                if (current != null) {
                    current.raw.append(tag)
                    if (name == current.tagName && !selfClosing) current.depth += if (closing) -1 else 1
                    if (current.depth == 0) {
                        pieces += Piece(current.raw.toString(), counted = current.counted, colored = false)
                        group = null
                    }
                    continue
                }
                when {
                    name == null || closing || (selfClosing && name != "img") -> pieces += Piece(tag, counted = false, colored = false)
                    name == "img" -> pieces += Piece(tag, counted = true, colored = false)
                    name in VERBATIM_TAGS || tag.contains("data-mx-maths") -> group = Group(name, counted = false)
                    name == "a" && tag.contains("matrix.to/#/") -> group = Group(name, counted = true)
                    else -> pieces += Piece(tag, counted = false, colored = false)
                }
                group?.raw?.append(tag)
            } else {
                val next = html.indexOf('<', i).let { if (it < 0) html.length else it }
                val text = html.substring(i, next)
                i = next
                val current = group
                if (current != null) current.raw.append(text) else pieces += textPieces(text)
            }
        }
        group?.let { pieces += Piece(it.raw.toString(), counted = it.counted, colored = false) }
        return pieces
    }

    private fun textPieces(text: String): List<Piece> {
        val pieces = mutableListOf<Piece>()
        fun addRun(run: String) {
            run.splitEmoji().forEach { grapheme ->
                val letter = grapheme.toString()
                pieces += when {
                    letter == "\n" -> Piece(letter, counted = false, colored = false)
                    letter.isBlank() -> Piece(letter, counted = true, colored = false)
                    else -> Piece(letter, counted = true, colored = true)
                }
            }
        }
        var index = 0
        ENTITY.findAll(text).forEach { match ->
            if (match.range.first > index) addRun(text.substring(index, match.range.first))
            pieces += Piece(match.value, counted = true, colored = true)
            index = match.range.last + 1
        }
        if (index < text.length) addRun(text.substring(index))
        return pieces
    }

    // nheko's rainbowify gradient: HSL(hue, 0.9, 0.5), hue swept over 5/6 of the color wheel.
    private fun hueColor(hue: Double): String {
        val x = 0.9 * (1 - abs((hue / 60) % 2 - 1))
        val (r, g, b) = when {
            hue < 60 -> Triple(0.9, x, 0.0)
            hue < 120 -> Triple(x, 0.9, 0.0)
            hue < 180 -> Triple(0.0, 0.9, x)
            hue < 240 -> Triple(0.0, x, 0.9)
            hue < 300 -> Triple(x, 0.0, 0.9)
            else -> Triple(0.9, 0.0, x)
        }
        return dashColor(listOf(r, g, b).map { ((it + 0.05) * 255).roundToInt() })
    }

    private fun stopsColor(stops: List<IntArray>, fraction: Double): String {
        if (stops.size == 1) return dashColor(stops.single().toList())
        val scaled = fraction * (stops.size - 1)
        val segment = scaled.toInt().coerceAtMost(stops.size - 2)
        val segmentFraction = scaled - segment
        val from = stops[segment]
        val to = stops[segment + 1]
        return dashColor(from.indices.map { (from[it] + (to[it] - from[it]) * segmentFraction).roundToInt() })
    }

    private fun dashColor(channels: List<Int>): String =
            channels.joinToString(separator = "", prefix = "#") { it.toString(16).padStart(2, '0') }

    companion object {
        // Trans flag stripes: light blue, pink, white, pink, light blue.
        private val TRANS_STOPS = listOf(
                intArrayOf(0x5b, 0xce, 0xfa),
                intArrayOf(0xf5, 0xa9, 0xb8),
                intArrayOf(0xff, 0xff, 0xff),
                intArrayOf(0xf5, 0xa9, 0xb8),
                intArrayOf(0x5b, 0xce, 0xfa),
        )
        private val TAG_NAME = Regex("^</?([a-zA-Z][a-zA-Z0-9-]*)")
        private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);")
        private val VERBATIM_TAGS = setOf("code", "pre")
    }
}
