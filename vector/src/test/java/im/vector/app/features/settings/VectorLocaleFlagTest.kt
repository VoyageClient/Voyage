/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import java.util.Locale

class VectorLocaleFlagTest {

    private val vectorLocale = VectorLocale(mockk(), mockk(), mockk())

    @Test
    fun `country code maps to regional indicator pair`() {
        vectorLocale.localeToFlagEmoji(Locale("en", "US")) shouldBeEqualTo "🇺🇸"
        vectorLocale.localeToFlagEmoji(Locale("zh", "TW")) shouldBeEqualTo "🇹🇼"
    }

    @Test
    fun `region-less locale falls back to the language's main country`() {
        vectorLocale.localeToFlagEmoji(Locale("ar")) shouldBeEqualTo "🇸🇦"
    }

    @Test
    fun `unknown region-less locale has no flag`() {
        vectorLocale.localeToFlagEmoji(Locale("eo")).shouldBeNull()
    }
}
