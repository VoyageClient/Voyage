/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import im.vector.lib.core.utils.text.neutralizeDirectionOverrides
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class StringExtensionsTest {

    @Test
    fun `given text with RtL unicode override, when checking contains RtL Override, then returns true`() {
        val textWithRtlOverride = "hello\u202Eworld"

        val result = textWithRtlOverride.containsRtLOverride()

        result shouldBeEqualTo true
    }

    @Test
    fun `given text without RtL unicode override, when checking contains RtL Override, then returns false`() {
        val textWithRtlOverride = "hello world"

        val result = textWithRtlOverride.containsRtLOverride()

        result shouldBeEqualTo false
    }

    @Test
    fun `given text with RtL unicode override, when ensuring ends LtR, then appends a LtR unicode override`() {
        val textWithRtlOverride = "123\u202E456"

        val result = textWithRtlOverride.ensureEndsLeftToRight()

        result shouldBeEqualTo "$textWithRtlOverride\u202D"
    }

    @Test
    fun `given text with unicode direction overrides, when filtering direction overrides, then removes all overrides`() {
        val textWithDirectionOverrides = "123\u202E456\u202d789"

        val result = textWithDirectionOverrides.filterDirectionOverrides()

        result shouldBeEqualTo "123456789"
    }

    @Test
    fun `given text with direction override characters, when neutralizing, then replaces each with a visible box`() {
        val hostileText = "abc\u202Edef\u202aghi"

        val result = hostileText.neutralizeDirectionOverrides()

        result shouldBeEqualTo "abc\ufdd0def\ufdd0ghi"
    }

    @Test
    fun `given text with direction marks and isolates, when neutralizing, then leaves it untouched`() {
        val legitimateText = "\u2068\u0645\u0631\u062d\u0628\u0627\u2069 \u200eabc"

        val result = legitimateText.neutralizeDirectionOverrides()

        result shouldBeEqualTo legitimateText
    }
}
