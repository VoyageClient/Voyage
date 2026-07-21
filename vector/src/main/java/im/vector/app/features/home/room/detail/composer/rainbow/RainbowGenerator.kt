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

    fun generate(text: String) = colorize(text) { idx, size -> hueColor(idx * 300.0 / size) }

    fun generateTrans(text: String) = colorize(text) { idx, size ->
        stopsColor(TRANS_STOPS, if (size == 1) 0.0 else idx / (size - 1.0))
    }

    private fun colorize(text: String, color: (idx: Int, size: Int) -> String): String {
        val split = text.splitEmoji()
        return split
                .mapIndexed { idx, letter ->
                    if (letter == " ") {
                        "$letter"
                    } else {
                        "<font color=\"${color(idx, split.size)}\">$letter</font>"
                    }
                }
                .joinToString(separator = "")
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
    }
}
