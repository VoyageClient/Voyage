/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.utils

import androidx.annotation.ColorInt

/**
 * Nudges a color's OKLab lightness, keeping its hue, until it reaches a WCAG 2 contrast ratio
 * against every given background. A color that already passes is returned unchanged.
 */
object ReadableColor {

    private val WHITE = doubleArrayOf(1.0, 1.0, 1.0)
    private val BLACK = doubleArrayOf(0.0, 0.0, 0.0)
    private const val STEPS = 24

    @ColorInt
    fun ensureContrast(@ColorInt color: Int, grounds: IntArray, minRatio: Double): Int {
        if (grounds.isEmpty()) return color
        val groundRgbs = grounds.map(::toRgb)
        val passes = { candidate: DoubleArray -> groundRgbs.all { ratio(candidate, it) >= minRatio } }
        val rgb = toRgb(color)
        if (passes(rgb)) return color
        val darkestGround = groundRgbs.minByOrNull(::luminance)!!
        val lighten = ratio(darkestGround, WHITE) >= ratio(darkestGround, BLACK)
        return toColor(shift(rgb, lighten, passes), color ushr 24)
    }

    fun contrastRatio(@ColorInt first: Int, @ColorInt second: Int): Double = ratio(toRgb(first), toRgb(second))

    private fun shift(rgb: DoubleArray, lighten: Boolean, passes: (DoubleArray) -> Boolean): DoubleArray {
        val lab = toOklab(rgb)
        var near = lab[0]
        var far = if (lighten) 1.0 else 0.0
        repeat(STEPS) {
            val lightness = (near + far) / 2
            if (passes(rounded(atLightness(lab, lightness)))) far = lightness else near = lightness
        }
        return rounded(atLightness(lab, far))
    }

    // Shrinks chroma until the color at this lightness fits in sRGB, so the hue survives.
    private fun atLightness(lab: DoubleArray, lightness: Double): DoubleArray {
        val direct = fromOklab(lightness, lab[1], lab[2])
        if (inGamut(direct)) return direct
        var low = 0.0
        var high = 1.0
        repeat(STEPS) {
            val scale = (low + high) / 2
            if (inGamut(fromOklab(lightness, lab[1] * scale, lab[2] * scale))) low = scale else high = scale
        }
        return fromOklab(lightness, lab[1] * low, lab[2] * low)
    }

    private fun inGamut(rgb: DoubleArray) = rgb.all { it >= -1e-4 && it <= 1 + 1e-4 }

    private fun rounded(rgb: DoubleArray) = DoubleArray(3) { Math.round(rgb[it].coerceIn(0.0, 1.0) * 255) / 255.0 }

    private fun toRgb(@ColorInt color: Int) = doubleArrayOf(
            (color shr 16 and 0xFF) / 255.0,
            (color shr 8 and 0xFF) / 255.0,
            (color and 0xFF) / 255.0,
    )

    @ColorInt
    private fun toColor(rgb: DoubleArray, alpha: Int): Int {
        fun channel(i: Int) = Math.round(rgb[i].coerceIn(0.0, 1.0) * 255).toInt()
        return (alpha shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
    }

    private fun toLinear(c: Double) = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)

    private fun fromLinear(c: Double) = if (c <= 0.0031308) c * 12.92 else 1.055 * Math.pow(c, 1 / 2.4) - 0.055

    private fun luminance(rgb: DoubleArray) =
            0.2126 * toLinear(rgb[0]) + 0.7152 * toLinear(rgb[1]) + 0.0722 * toLinear(rgb[2])

    private fun ratio(first: DoubleArray, second: DoubleArray): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun toOklab(rgb: DoubleArray): DoubleArray {
        val r = toLinear(rgb[0])
        val g = toLinear(rgb[1])
        val b = toLinear(rgb[2])
        val l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return doubleArrayOf(
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    private fun fromOklab(lightness: Double, a: Double, b: Double): DoubleArray {
        val l0 = lightness + 0.3963377774 * a + 0.2158037573 * b
        val m0 = lightness - 0.1055613458 * a - 0.0638541728 * b
        val s0 = lightness - 0.0894841775 * a - 1.2914855480 * b
        val l = l0 * l0 * l0
        val m = m0 * m0 * m0
        val s = s0 * s0 * s0
        return doubleArrayOf(
                fromLinear(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
                fromLinear(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
                fromLinear(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s),
        )
    }
}
