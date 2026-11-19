/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import android.graphics.Color
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.ui.colorpicker.ColorPalette
import im.vector.app.core.utils.ReadableColor
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.ThemeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.profile.ColorPreference
import org.matrix.android.sdk.api.util.MatrixItem
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReadableNameColorTest {

    private val context = RuntimeEnvironment.getApplication().apply {
        setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light)
    }

    private var correct = true

    private val vectorPreferences = mockk<VectorPreferences>(relaxed = true).also {
        every { it.peopleColorPalette() } returns ColorPalette.MODERN
        every { it.roomColorPalette() } returns ColorPalette.MODERN
        every { it.showOthersProfileColors() } returns true
        every { it.correctNameColorContrast() } answers { correct }
        every { it.useMessageBubblesLayout() } returns false
    }

    private val sessionHolder = mockk<ActiveSessionHolder>(relaxed = true).also {
        every { it.getSafeActiveSession() } returns null
    }

    private val colorProvider = ColorProvider(context)

    private val provider = MatrixItemColorProvider(
            colorProvider = colorProvider,
            vectorPreferences = vectorPreferences,
            themeProvider = ThemeProvider(context),
            activeSessionHolder = { sessionHolder },
    )

    private val ground get() = colorProvider.getColorFromAttribute(android.R.attr.colorBackground)

    private fun alice(hex: String? = null) = MatrixItem.UserItem(
            "@alice:example.org",
            "Alice",
            colorPreference = hex?.let { ColorPreference.fromHex(it) },
    )

    @Test
    fun `a faint chosen color is made readable on the theme background`() {
        val name = provider.getNameColor(alice("#F0F0A0"))
        assertNotEquals(Color.parseColor("#F0F0A0"), name)
        assertTrue(ReadableColor.contrastRatio(name, ground) >= 3.0)
    }

    @Test
    fun `a readable chosen color is left alone`() {
        assertEquals(Color.parseColor("#00548C"), provider.getNameColor(alice("#00548C")))
    }

    @Test
    fun `avatars keep the chosen color as picked`() {
        assertEquals(Color.parseColor("#F0F0A0"), provider.getColor(alice("#F0F0A0")))
    }

    @Test
    fun `palette defaults are never adjusted`() {
        assertEquals(provider.defaultColor(alice()), provider.getNameColor(alice()))
    }

    @Test
    fun `turning the setting off shows the chosen color as picked`() {
        val corrected = provider.getNameColor(alice("#F0F0A0"))
        correct = false
        provider.invalidate()
        val raw = provider.getNameColor(alice("#F0F0A0"))
        assertEquals(Color.parseColor("#F0F0A0"), raw)
        assertNotEquals(corrected, raw)
    }
}
