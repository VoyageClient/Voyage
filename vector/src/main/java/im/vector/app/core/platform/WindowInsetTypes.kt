/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.platform

import androidx.core.view.WindowInsetsCompat

object WindowInsetTypes {

    fun rootPaddingTypes(hasFocusedTextEditor: Boolean): Int {
        val base = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        return if (hasFocusedTextEditor) base or WindowInsetsCompat.Type.ime() else base
    }
}
