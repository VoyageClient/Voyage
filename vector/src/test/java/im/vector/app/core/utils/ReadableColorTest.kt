/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadableColorTest {

    private fun rgb(hex: Long) = (0xFF000000 or hex).toInt()

    // Contrast against black rises with luminance, so it orders colors by brightness.
    private fun brightnessOf(color: Int) = ReadableColor.contrastRatio(color, rgb(0x000000))

    // OKLab holds perceived hue, which HSV hue doesn't track, so check the hue family instead.
    private fun dominantChannelOf(color: Int): Int =
            listOf(16, 8, 0).maxByOrNull { color shr it and 0xFF }!!

    private fun assertReadable(color: Int, grounds: IntArray, min: Double = 3.0) {
        grounds.forEach { assertTrue("ratio vs ${Integer.toHexString(it)}", ReadableColor.contrastRatio(color, it) >= min) }
    }

    @Test
    fun `a color that already passes is returned unchanged`() {
        val color = rgb(0x56C02C)
        assertEquals(color, ReadableColor.ensureContrast(color, intArrayOf(rgb(0x21262C)), 3.0))
    }

    @Test
    fun `a light color on a light ground is darkened just enough, keeping its hue`() {
        val color = rgb(0x74D12C)
        val ground = rgb(0xEEEEEE)
        val result = ReadableColor.ensureContrast(color, intArrayOf(ground), 3.0)
        assertReadable(result, intArrayOf(ground))
        assertTrue(brightnessOf(result) < brightnessOf(color))
        assertTrue(ReadableColor.contrastRatio(result, ground) < 3.3)
        assertEquals(dominantChannelOf(color), dominantChannelOf(result))
    }

    @Test
    fun `a dark color on a dark ground is lightened`() {
        val color = rgb(0x000080)
        val ground = rgb(0x161616)
        val result = ReadableColor.ensureContrast(color, intArrayOf(ground), 3.0)
        assertReadable(result, intArrayOf(ground))
        assertTrue(brightnessOf(result) > brightnessOf(color))
        assertEquals(dominantChannelOf(color), dominantChannelOf(result))
    }

    @Test
    fun `every ground must pass, including a mid-tone bubble`() {
        val grounds = intArrayOf(rgb(0x21262C), rgb(0x465561))
        val result = ReadableColor.ensureContrast(rgb(0x5C56F5), grounds, 3.0)
        assertReadable(result, grounds)
    }

    @Test
    fun `saturated primaries stay valid colors`() {
        listOf(0xFF0000L, 0x0000FFL, 0x00FF00L).forEach { hex ->
            val darkResult = ReadableColor.ensureContrast(rgb(hex), intArrayOf(rgb(0x000000)), 4.5)
            assertReadable(darkResult, intArrayOf(rgb(0x000000)), 4.5)
            val lightResult = ReadableColor.ensureContrast(rgb(hex), intArrayOf(rgb(0xFFFFFF)), 4.5)
            assertReadable(lightResult, intArrayOf(rgb(0xFFFFFF)), 4.5)
        }
    }

    @Test
    fun `alpha is kept`() {
        val color = (0x80F0F0A0).toInt()
        val result = ReadableColor.ensureContrast(color, intArrayOf(rgb(0xFFFFFF)), 3.0)
        assertNotEquals(color, result)
        assertEquals(0x80, result ushr 24)
    }

    @Test
    fun `no grounds means no change`() {
        val color = rgb(0xF0F0A0)
        assertEquals(color, ReadableColor.ensureContrast(color, IntArray(0), 3.0))
    }
}
