/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PillSpanStyleTest {

    @Test
    fun `a style around just the mention stays on the pill`() {
        val text = SpannableStringBuilder("x nv tests")
        val bold = StyleSpan(Typeface.BOLD)
        val italic = StyleSpan(Typeface.ITALIC)
        text.setSpan(italic, 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(bold, 2, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        text.setPillSpan(mockk<PillImageSpan>(relaxed = true), 2, 4)

        assertEquals("x $PILL_PLACEHOLDER tests", text.toString())
        assertEquals(2, text.getSpanStart(bold))
        assertEquals(2 + PILL_PLACEHOLDER.length, text.getSpanEnd(bold))
        assertEquals(0, text.getSpanStart(italic))
        assertEquals(text.length, text.getSpanEnd(italic))
    }
}
