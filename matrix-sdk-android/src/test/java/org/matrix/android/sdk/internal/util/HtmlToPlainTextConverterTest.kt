/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.util

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class HtmlToPlainTextConverterTest {

    @Test
    fun `a custom emote converts to its shortcode, not a placeholder`() {
        "hi <img data-mx-emoticon src=\"mxc://x/y\" alt=\":blob:\" title=\":blob:\" height=\"32\" /> there".unescapeHtml() shouldBeEqualTo
                "hi :blob: there"
    }

    @Test
    fun `an image without alt text falls back to its title, then to nothing`() {
        "<img src='mxc://x/y' title='cat'>".unescapeHtml() shouldBeEqualTo "cat"
        "a<img src=\"mxc://x/y\">b".unescapeHtml() shouldBeEqualTo "ab"
    }

    @Test
    fun `escaped alt text is unescaped with the rest`() {
        "<img alt=\"&lt;3\" src=\"mxc://x/y\">".unescapeHtml() shouldBeEqualTo "<3"
    }
}
