/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.colorpicker

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ColorPaletteMemoryTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `the most recent pick of a color wins, ignoring case`() {
        ColorPaletteMemory.remember(context, setOf("#ff812d"), ColorPalette.LEGACY)
        ColorPaletteMemory.remember(context, setOf("#FF812D", "#AA0000"), ColorPalette.DISCORD_2021)

        ColorPaletteMemory.paletteFor(context, "#FF812D") shouldBeEqualTo ColorPalette.DISCORD_2021
        ColorPaletteMemory.paletteFor(context, "#aa0000") shouldBeEqualTo ColorPalette.DISCORD_2021
        ColorPaletteMemory.paletteFor(context, "#000001") shouldBeEqualTo null
    }

    @Test
    fun `the last custom color is kept on its own`() {
        ColorPaletteMemory.lastCustom(context) shouldBeEqualTo null
        ColorPaletteMemory.rememberCustom(context, "#12ab34")

        ColorPaletteMemory.lastCustom(context) shouldBeEqualTo "#12AB34"
        ColorPaletteMemory.paletteFor(context, "#12AB34") shouldBeEqualTo null
    }

    @Test
    fun `old picks are dropped first once the memory is full`() {
        repeat(300) { ColorPaletteMemory.remember(context, setOf("#%06X".format(it)), ColorPalette.MODERN) }

        ColorPaletteMemory.paletteFor(context, "#000000") shouldBeEqualTo null
        ColorPaletteMemory.paletteFor(context, "#00012B") shouldBeEqualTo ColorPalette.MODERN
    }
}
