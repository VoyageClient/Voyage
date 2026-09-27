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
    fun `a visible keyboard reserves space even when message selection has focus`() {
        (WindowInsetTypes.rootPaddingTypes(reserveImeSpace = true) and ime) shouldBeEqualTo ime
    }

    @Test
    fun `a dismissed keyboard does not reserve space`() {
        (WindowInsetTypes.rootPaddingTypes(reserveImeSpace = false) and ime) shouldBeEqualTo 0
    }

    @Test
    fun `system bars and the cutout are padded either way`() {
        val always = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

        (WindowInsetTypes.rootPaddingTypes(reserveImeSpace = true) and always) shouldBeEqualTo always
        (WindowInsetTypes.rootPaddingTypes(reserveImeSpace = false) and always) shouldBeEqualTo always
    }
}
