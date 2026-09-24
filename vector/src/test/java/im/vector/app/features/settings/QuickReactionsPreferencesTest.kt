/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import androidx.preference.PreferenceManager
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QuickReactionsPreferencesTest {

    private val context = RuntimeEnvironment.getApplication()
    private val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)

    private val vectorPreferences = VectorPreferences(
            context = context,
            clock = mockk(relaxed = true),
            buildMeta = mockk(relaxed = true),
            vectorFeatures = mockk(relaxed = true),
            defaultPrefs = defaultPrefs,
            stringProvider = mockk(relaxed = true),
    )

    @Test
    fun `multi-word reactions round-trip intact`() {
        val reactions = listOf("👍️", "can element do this", "[bracketed]", "mxc://example.org/abc")

        vectorPreferences.setQuickReactions(reactions)

        vectorPreferences.getQuickReactions() shouldBeEqualTo reactions
    }

    @Test
    fun `legacy space-joined values are still read`() {
        defaultPrefs.edit().putString(VectorPreferences.SETTINGS_QUICK_REACTIONS_KEY, "👍️ 🎉 👀").commit()

        vectorPreferences.getQuickReactions() shouldBeEqualTo listOf("👍️", "🎉", "👀")
    }
}
