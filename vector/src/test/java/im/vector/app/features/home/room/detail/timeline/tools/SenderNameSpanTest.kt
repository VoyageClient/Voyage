/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.tools

import android.graphics.Typeface
import android.text.Spanned
import android.text.TextPaint
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.ui.colorpicker.ColorPalette
import im.vector.app.features.home.room.detail.timeline.helper.MatrixItemColorProvider
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.themes.ThemeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.util.MatrixItem
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The sender name leading an emote is emphasized like a display name: colored, and bold while colored. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SenderNameSpanTest {

    private val context = RuntimeEnvironment.getApplication().apply {
        setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light)
    }

    private val alice = MatrixItem.UserItem("@alice:example.org", "Alice")

    private var palette = ColorPalette.MODERN

    private val vectorPreferences = mockk<VectorPreferences>(relaxed = true).also {
        every { it.peopleColorPalette() } answers { palette }
        every { it.roomColorPalette() } returns ColorPalette.MODERN
        every { it.showOthersProfileColors() } returns true
    }

    // No session, so colors come from the palette hash rather than a (mocked) profile color.
    private val sessionHolder = mockk<ActiveSessionHolder>(relaxed = true).also {
        every { it.getSafeActiveSession() } returns null
    }

    private val colorProvider = MatrixItemColorProvider(
            colorProvider = ColorProvider(context),
            vectorPreferences = vectorPreferences,
            themeProvider = ThemeProvider(context),
            activeSessionHolder = { sessionHolder },
    )

    private fun paintOf(): TextPaint = TextPaint().apply { typeface = Typeface.DEFAULT }

    private fun span() = SenderNameSpan(alice, colorProvider)

    private fun Typeface.isStyleBold() = style and Typeface.BOLD != 0

    @Test
    fun `the span covers only the sender name`() {
        val body = "waves".asEmoteBody("Alice", span()) as Spanned
        val applied = body.getSpans(0, body.length, SenderNameSpan::class.java).single()
        assertEquals(0 to "Alice".length, body.getSpanStart(applied) to body.getSpanEnd(applied))
    }

    @Test
    fun `a colored name takes the sender color and turns bold`() {
        val paint = paintOf()
        span().updateDrawState(paint)
        assertEquals(colorProvider.getNameColor(alice), paint.color)
        assertTrue(paint.typeface.isStyleBold())
    }

    @Test
    fun `an uncolored name stays regular weight`() {
        palette = ColorPalette.NONE
        val paint = paintOf()
        span().updateDrawState(paint)
        assertEquals(colorProvider.getNameColor(alice), paint.color)
        assertEquals(false, paint.typeface.isStyleBold())
    }

    @Test
    fun `the italic emote line stays italic under a bold name`() {
        val paint = TextPaint().apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC) }
        span().updateMeasureState(paint)
        assertTrue(paint.typeface.isStyleBold())
        assertTrue(paint.typeface.style and Typeface.ITALIC != 0)
    }

    @Test
    fun `a palette change repaints an already-resolved span`() {
        val span = span()
        val paint = paintOf()
        span.updateDrawState(paint)
        val colored = paint.color

        palette = ColorPalette.NONE
        colorProvider.invalidate()
        span.updateDrawState(paint)
        assertNotEquals(colored, paint.color)
        assertEquals(colorProvider.getNameColor(alice), paint.color)
    }
}
