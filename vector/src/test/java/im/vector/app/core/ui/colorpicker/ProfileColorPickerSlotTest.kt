/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.colorpicker

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class ProfileColorPickerSlotTest {

    private val shared = "#123456"

    // Every resource gets its own hex unless it is one of [sharedResources].
    private fun hexOf(sharedResources: Set<Int>): (Int) -> String = { res ->
        if (res in sharedResources) shared else "#%06X".format(res and 0xFFFFFF)
    }

    @Test
    fun `a color shared by palettes resolves to the first candidate`() {
        val legacy = ColorPalette.LEGACY.colors[2].onLight
        val modern = ColorPalette.MODERN.colors[1].onLight
        val hexOf = hexOf(setOf(legacy, modern))

        ProfileColorPickerDialogFragment.findSlot(listOf(ColorPalette.MODERN, ColorPalette.LEGACY), shared, hexOf, light = true) shouldBeEqualTo
                (ColorPalette.MODERN to 1)
        ProfileColorPickerDialogFragment.findSlot(listOf(ColorPalette.LEGACY, ColorPalette.MODERN), shared, hexOf, light = true) shouldBeEqualTo
                (ColorPalette.LEGACY to 2)
    }

    @Test
    fun `this theme's variant wins over the other theme's`() {
        val modernDark = ColorPalette.MODERN.colors[0].onDark
        val legacy = ColorPalette.LEGACY.colors[0].onLight
        val hexOf = hexOf(setOf(modernDark, legacy))

        ProfileColorPickerDialogFragment.findSlot(listOf(ColorPalette.MODERN, ColorPalette.LEGACY), shared, hexOf, light = true) shouldBeEqualTo
                (ColorPalette.LEGACY to 0)
    }

    @Test
    fun `a color stored for the other theme still finds its palette`() {
        val hexOf = hexOf(setOf(ColorPalette.MODERN.colors[3].onDark))

        ProfileColorPickerDialogFragment.findSlot(listOf(ColorPalette.LEGACY, ColorPalette.MODERN), shared, hexOf, light = true) shouldBeEqualTo
                (ColorPalette.MODERN to 3)
    }

    @Test
    fun `a color in no palette is not found`() {
        ProfileColorPickerDialogFragment.findSlot(ColorPalette.values().toList(), shared, hexOf(emptySet()), light = true) shouldBeEqualTo null
    }
}
