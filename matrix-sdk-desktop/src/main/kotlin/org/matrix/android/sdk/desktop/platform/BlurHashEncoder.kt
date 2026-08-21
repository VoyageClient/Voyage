/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.desktop.platform

import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/** The reference blurhash encoder (woltapp/blurhash) over a BufferedImage. */
internal object BlurHashEncoder {

    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"

    fun encode(image: BufferedImage, componentX: Int, componentY: Int): String {
        require(componentX in 1..9 && componentY in 1..9)
        val width = image.width
        val height = image.height
        val pixels = image.getRGB(0, 0, width, height, null, 0, width)
        val linear = Array(3) { channel ->
            DoubleArray(width * height) { i -> sRgbToLinear((pixels[i] shr (16 - 8 * channel)) and 0xFF) }
        }
        val factors = ArrayList<DoubleArray>(componentX * componentY)
        for (y in 0 until componentY) {
            for (x in 0 until componentX) {
                val normalisation = if (x == 0 && y == 0) 1.0 else 2.0
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (py in 0 until height) {
                    val cy = cos(PI * y * py / height)
                    for (px in 0 until width) {
                        val basis = normalisation * cos(PI * x * px / width) * cy
                        val i = py * width + px
                        r += basis * linear[0][i]
                        g += basis * linear[1][i]
                        b += basis * linear[2][i]
                    }
                }
                val scale = 1.0 / (width * height)
                factors.add(doubleArrayOf(r * scale, g * scale, b * scale))
            }
        }
        val dc = factors[0]
        val ac = factors.drop(1)
        val out = StringBuilder()
        out.encode83((componentX - 1) + (componentY - 1) * 9, 1)
        val maximumValue: Double
        if (ac.isNotEmpty()) {
            val actualMax = ac.maxOf { f -> f.maxOf { abs(it) } }
            val quantised = (actualMax * 166 - 0.5).roundToInt().coerceIn(0, 82)
            maximumValue = (quantised + 1) / 166.0
            out.encode83(quantised, 1)
        } else {
            maximumValue = 1.0
            out.encode83(0, 1)
        }
        out.encode83(encodeDc(dc), 4)
        ac.forEach { out.encode83(encodeAc(it, maximumValue), 2) }
        return out.toString()
    }

    private fun encodeDc(value: DoubleArray): Int =
            (linearToSRgb(value[0]) shl 16) + (linearToSRgb(value[1]) shl 8) + linearToSRgb(value[2])

    private fun encodeAc(value: DoubleArray, maximumValue: Double): Int {
        fun quantise(v: Double) = (signPow(v / maximumValue, 0.5) * 9 + 9.5).toInt().coerceIn(0, 18)
        return quantise(value[0]) * 19 * 19 + quantise(value[1]) * 19 + quantise(value[2])
    }

    private fun signPow(value: Double, exp: Double) = sign(value) * abs(value).pow(exp)

    private fun sRgbToLinear(value: Int): Double {
        val v = value / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun linearToSRgb(value: Double): Int {
        val v = value.coerceIn(0.0, 1.0)
        val srgb = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
        return (srgb * 255 + 0.5).toInt()
    }

    private fun StringBuilder.encode83(value: Int, length: Int) {
        for (i in 1..length) {
            val digit = (value / 83.0.pow(length - i).toInt()) % 83
            append(ALPHABET[digit])
        }
    }
}
