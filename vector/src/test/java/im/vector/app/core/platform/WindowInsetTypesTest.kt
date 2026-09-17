/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.platform

import androidx.core.view.WindowInsetsCompat
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class WindowInsetTypesTest {

    private val ime = WindowInsetsCompat.Type.ime()

    @Test
    fun `a focused text editor pads for the keyboard`() {
        (WindowInsetTypes.rootPaddingTypes(hasFocusedTextEditor = true) and ime) shouldBeEqualTo ime
    }

    @Test
    fun `a window without a focused text editor ignores the keyboard inset`() {
        (WindowInsetTypes.rootPaddingTypes(hasFocusedTextEditor = false) and ime) shouldBeEqualTo 0
    }

    @Test
    fun `system bars and the cutout are padded either way`() {
        val always = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

        (WindowInsetTypes.rootPaddingTypes(hasFocusedTextEditor = true) and always) shouldBeEqualTo always
        (WindowInsetTypes.rootPaddingTypes(hasFocusedTextEditor = false) and always) shouldBeEqualTo always
    }
}
