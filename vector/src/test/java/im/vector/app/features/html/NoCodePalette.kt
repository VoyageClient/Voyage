/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import im.vector.app.features.html.highlight.CodeBlockHighlighter
import io.mockk.every
import io.mockk.mockk

/** A highlighter whose palette leaves code in the theme's own colors. */
fun noCodePalette(): CodeBlockHighlighter = mockk {
    every { theme(any(), any()) } returns null
}
