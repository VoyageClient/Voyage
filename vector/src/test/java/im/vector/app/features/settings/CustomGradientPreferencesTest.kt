/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import androidx.preference.PreferenceManager
import im.vector.app.features.settings.gradients.CustomGradient
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CustomGradientPreferencesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val vectorPreferences = VectorPreferences(context, mockk(relaxed = true), mockk(relaxed = true), prefs)

    @Test
    fun `gradients retain labels and ordered repeated colors across reloads`() {
        val gradients = listOf(
                CustomGradient("sunset", listOf(0x123456, 0xFFFFFF, 0x123456)),
                CustomGradient("solid", listOf(0xABCDEF)),
        )
        vectorPreferences.setCustomGradients(gradients)

        val reloaded = VectorPreferences(context, mockk(relaxed = true), mockk(relaxed = true), prefs)
        reloaded.getCustomGradients() shouldBeEqualTo gradients

        reloaded.setCustomGradients(emptyList())
        vectorPreferences.getCustomGradients() shouldBeEqualTo emptyList()
    }

    @Test
    fun `duplicate labels and invalid palettes cannot overwrite saved gradients`() {
        val original = listOf(CustomGradient("sunset", listOf(0, 0xFFFFFF)))
        vectorPreferences.setCustomGradients(original)

        runCatching { vectorPreferences.setCustomGradients(original + CustomGradient("SUNSET", listOf(1, 2))) }
                .isFailure shouldBeEqualTo true
        listOf(
                CustomGradient("two words", listOf(1, 2)),
                CustomGradient("", listOf(1, 2)),
                CustomGradient("sunset", emptyList()),
                CustomGradient("sunset", listOf(-1, 2)),
                CustomGradient("sunset", List(CustomGradient.MAX_COLORS + 1) { 0 }),
        ).forEach { gradient ->
            runCatching { vectorPreferences.setCustomGradients(listOf(gradient)) }.isFailure shouldBeEqualTo true
        }
        vectorPreferences.getCustomGradients() shouldBeEqualTo original
    }

    @Test
    fun `malformed entries are skipped without losing valid gradients`() {
        prefs.edit().putString(
                "SETTINGS_CUSTOM_GRADIENTS",
                """[{"label":"broken","colors":[]},{"label":"valid","colors":[0,16777215]},{"label":"VALID","colors":[1,2]}]""",
        ).commit()

        vectorPreferences.getCustomGradients() shouldBeEqualTo listOf(CustomGradient("valid", listOf(0, 0xFFFFFF)))
    }
}
