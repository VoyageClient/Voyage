/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.colorpicker

import androidx.core.content.ContextCompat
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import im.vector.lib.ui.styles.R as StylesR

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProfileColorDescribeTest {

    private val context = RuntimeEnvironment.getApplication()

    // element_name_alpha_07, the palette entry named "Indigo".
    private val paletteHex = MatrixItemColorProvider.toHex(ContextCompat.getColor(context, StylesR.color.element_name_alpha_07))

    private fun describe(hex: String, origin: ProfileColorPickerDialogFragment.Origin) =
            ProfileColorPickerDialogFragment.describe(context, hex, light = true, origin = origin)

    @Test
    fun `given nobody chose the color, when described, then it is the default with its palette name`() {
        describe(paletteHex, ProfileColorPickerDialogFragment.Origin.DEFAULT) shouldBeEqualTo "Default ($paletteHex, Indigo)"
    }

    @Test
    fun `given the user chose their own color, when described, then it is custom with the name appended`() {
        describe(paletteHex, ProfileColorPickerDialogFragment.Origin.THEIRS) shouldBeEqualTo "Custom ($paletteHex, Indigo)"
    }

    @Test
    fun `given we picked that palette color ourselves, when described, then the name leads`() {
        describe(paletteHex, ProfileColorPickerDialogFragment.Origin.OURS) shouldBeEqualTo "Indigo ($paletteHex)"
    }

    @Test
    fun `given a color in no palette, when described, then each origin falls back to its bare form`() {
        describe("#123456", ProfileColorPickerDialogFragment.Origin.DEFAULT) shouldBeEqualTo "Default (#123456)"
        describe("#123456", ProfileColorPickerDialogFragment.Origin.THEIRS) shouldBeEqualTo "Custom (#123456)"
        describe("#123456", ProfileColorPickerDialogFragment.Origin.OURS) shouldBeEqualTo "Custom (#123456)"
    }

    @Test
    fun `given a lowercase or shorthand hex, when described, then it is normalized`() {
        describe("#abc", ProfileColorPickerDialogFragment.Origin.THEIRS) shouldBeEqualTo "Custom (#AABBCC)"
    }
}
