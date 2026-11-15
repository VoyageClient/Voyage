/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import androidx.core.content.edit
import androidx.preference.PreferenceManager
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PerformanceModePreferencesTest {

    private val context = RuntimeEnvironment.getApplication()
    private val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(context)

    private fun preferences() = VectorPreferences(
            context = context,
            buildMeta = mockk(relaxed = true),
            vectorFeatures = mockk(relaxed = true),
            defaultPrefs = defaultPrefs,
    )

    @Test
    fun `all preference combinations are forced and restored`() {
        for (autoplay in listOf(false, true)) {
            for (previews in listOf(false, true)) {
                for (solid in listOf(false, true)) {
                    defaultPrefs.edit {
                        clear()
                        putBoolean(VectorPreferences.SETTINGS_AUTOPLAY_ANIMATED_IMAGES, autoplay)
                        putBoolean(VectorPreferences.SETTINGS_SHOW_URL_PREVIEW_KEY, previews)
                        putBoolean(VectorPreferences.SETTINGS_MEDIA_PREVIEW_SOLID_KEY, solid)
                        putString(VectorPreferences.SETTINGS_SCROLL_ANIMATION_KEY, "flip")
                    }
                    val preferences = preferences()
                    val original = constrainedValues(preferences)

                    preferences.applyPerformanceModeConstraints(true)
                    assertEquals(listOf(false, false, true, "none"), constrainedValues(preferences))

                    preferences.applyPerformanceModeConstraints(false)
                    assertEquals(original, constrainedValues(preferences))
                }
            }
        }
    }

    @Test
    fun `default preferences are restored after repeated startup constraints`() {
        val original = constrainedValues(preferences())
        preferences().applyPerformanceModeConstraints(true)

        repeat(3) { preferences().applyPerformanceModeConstraints(true) }
        preferences().applyPerformanceModeConstraints(false)

        assertEquals(original, constrainedValues(preferences()))
    }

    @Test
    fun `a later activation saves the latest preferences`() {
        preferences().applyPerformanceModeConstraints(true)
        preferences().applyPerformanceModeConstraints(false)
        defaultPrefs.edit {
            putBoolean(VectorPreferences.SETTINGS_AUTOPLAY_ANIMATED_IMAGES, false)
            putBoolean(VectorPreferences.SETTINGS_SHOW_URL_PREVIEW_KEY, true)
            putBoolean(VectorPreferences.SETTINGS_MEDIA_PREVIEW_SOLID_KEY, true)
            putString(VectorPreferences.SETTINGS_SCROLL_ANIMATION_KEY, "jiggle")
        }
        val original = constrainedValues(preferences())

        preferences().applyPerformanceModeConstraints(false)
        preferences().applyPerformanceModeConstraints(true)
        preferences().applyPerformanceModeConstraints(false)

        assertEquals(original, constrainedValues(preferences()))
    }

    @Test
    fun `disabling without a saved snapshot leaves preferences alone`() {
        defaultPrefs.edit {
            putBoolean(VectorPreferences.SETTINGS_AUTOPLAY_ANIMATED_IMAGES, false)
            putBoolean(VectorPreferences.SETTINGS_MEDIA_PREVIEW_SOLID_KEY, true)
            putString(VectorPreferences.SETTINGS_SCROLL_ANIMATION_KEY, "fade")
        }
        val original = defaultPrefs.all

        preferences().applyPerformanceModeConstraints(false)

        assertEquals(original, defaultPrefs.all)
    }

    @Test
    fun `an animated shape is temporarily replaced without losing the saved choice`() {
        defaultPrefs.edit {
            putBoolean(VectorPreferences.SETTINGS_PERFORMANCE_MODE_KEY, false)
            putString(VectorPreferences.SETTINGS_AVATAR_SHAPE_KEY, AvatarShape.SPIN_360.storageKey)
        }
        val original = preferences().avatarShape()

        setPerformanceMode(true)
        assertEquals(AvatarShape.CIRCLE, preferences().avatarShape())
        assertEquals(AvatarShape.SPIN_360.storageKey, defaultPrefs.getString(VectorPreferences.SETTINGS_AVATAR_SHAPE_KEY, null))

        setPerformanceMode(false)
        assertEquals(original, preferences().avatarShape())
    }

    @Test
    fun `performance mode allows static selections and rejects animated ones`() {
        setPerformanceMode(true)
        preferences().setAvatarShape(AvatarShape.HEXAGON)

        preferences().setAvatarShape(AvatarShape.SPIN_360)

        assertEquals(AvatarShape.HEXAGON, preferences().avatarShape())
        setPerformanceMode(false)
        assertEquals(AvatarShape.HEXAGON, preferences().avatarShape())
    }

    private fun setPerformanceMode(enabled: Boolean) {
        preferences().applyPerformanceModeConstraints(enabled)
        defaultPrefs.edit { putBoolean(VectorPreferences.SETTINGS_PERFORMANCE_MODE_KEY, enabled) }
    }

    private fun constrainedValues(preferences: VectorPreferences): List<Any> = listOf(
            preferences.autoplayAnimatedImages(),
            preferences.showUrlPreviews(),
            preferences.useSolidColorForHiddenMedia(),
            preferences.scrollAnimationStyle(),
    )
}
