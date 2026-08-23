/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.profile

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test

class ColorPreferenceTest {

    @Test
    fun `normalizeHex expands short form and uppercases`() {
        ColorPreference.normalizeHex("#abc") shouldBeEqualTo "#AABBCC"
        ColorPreference.normalizeHex("#de7356") shouldBeEqualTo "#DE7356"
    }

    @Test
    fun `normalizeHex strips quotes`() {
        ColorPreference.normalizeHex("\"#DE7356\"") shouldBeEqualTo "#DE7356"
        ColorPreference.normalizeHex(" '#abc' ") shouldBeEqualTo "#AABBCC"
    }

    @Test
    fun `normalizeHex rejects alpha, names and non-strings`() {
        ColorPreference.normalizeHex("#12345678").shouldBeNull()
        ColorPreference.normalizeHex("red").shouldBeNull()
        ColorPreference.normalizeHex("DE7356").shouldBeNull()
        ColorPreference.normalizeHex(42).shouldBeNull()
        ColorPreference.normalizeHex(null).shouldBeNull()
    }

    @Test
    fun `parse reads members and drops empty objects`() {
        ColorPreference.parse(mapOf("on_light" to "#400", "on_dark" to null)) shouldBeEqualTo ColorPreference(onLight = "#440000")
        ColorPreference.parse(mapOf("on_light" to null, "on_dark" to null)).shouldBeNull()
        ColorPreference.parse(emptyMap<String, Any>()).shouldBeNull()
        ColorPreference.parse("#ffffff").shouldBeNull()
    }

    @Test
    fun `forTheme falls back to the other theme`() {
        ColorPreference(onLight = "#440000").forTheme(light = false) shouldBeEqualTo "#440000"
        ColorPreference(onDark = "#FFD9F5").forTheme(light = true) shouldBeEqualTo "#FFD9F5"
        ColorPreference(onLight = "#440000", onDark = "#FFD9F5").forTheme(light = true) shouldBeEqualTo "#440000"
    }
}
