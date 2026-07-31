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
class AutoplayAnimatedImagesDefaultTest {

    private val context = RuntimeEnvironment.getApplication()

    private val vectorPreferences = VectorPreferences(
            context = context,
            clock = mockk(relaxed = true),
            buildMeta = mockk(relaxed = true),
            vectorFeatures = mockk(relaxed = true),
            defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context),
            stringProvider = mockk(relaxed = true),
    )

    @Test
    fun `animated images autoplay out of the box`() {
        vectorPreferences.autoplayAnimatedImages() shouldBeEqualTo true
    }

    @Test
    fun `performance mode is the one thing that turns autoplay off on its own`() {
        vectorPreferences.applyPerformanceModeConstraints(performanceMode = true)

        vectorPreferences.autoplayAnimatedImages() shouldBeEqualTo false
    }

    @Test
    fun `leaving performance mode off never overrides the choice`() {
        vectorPreferences.applyPerformanceModeConstraints(performanceMode = false)

        vectorPreferences.autoplayAnimatedImages() shouldBeEqualTo true
    }
}
