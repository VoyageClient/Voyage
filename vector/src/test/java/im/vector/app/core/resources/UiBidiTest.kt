/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.resources

import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import im.vector.lib.core.utils.text.bidiIsolate
import im.vector.lib.core.utils.text.neutralizeDirectionOverrides
import im.vector.lib.core.utils.text.withUiDirection
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

private val LRM = Char(0x200E)
private val RLM = Char(0x200F)
private val FSI = Char(0x2068)
private val PDI = Char(0x2069)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UiBidiTest {

    private val defaultLocale = Locale.getDefault()

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun `an RTL name in an LTR UI sentence is isolated and the sentence pinned LTR`() {
        Locale.setDefault(Locale.ENGLISH)
        val sp = mockk<StringProvider> {
            every { getString(1, *anyVararg()) } answers { "%s joined the room".format(*arg<Array<Any?>>(1)) }
        }

        assertEquals("$LRM${FSI}علي$PDI joined the room", BidiSafeStringProvider(sp).getString(1, "علي"))
    }

    @Test
    fun `an RTL UI pins sentences RTL`() {
        Locale.setDefault(Locale("ar"))

        assertEquals("${RLM}Alice", "Alice".withUiDirection())
    }

    @Test
    fun `an already pinned sentence is left alone`() {
        Locale.setDefault(Locale.ENGLISH)

        assertEquals("${RLM}abc", "${RLM}abc".withUiDirection())
    }

    @Test
    @Config(sdk = [21])
    fun `before Marshmallow names are bracketed with UI direction marks`() {
        Locale.setDefault(Locale.ENGLISH)

        assertEquals("${LRM}علي$LRM", "علي".bidiIsolate())
    }

    @Test
    fun `isolating keeps spans and survives neutralization`() {
        val text = SpannableString("bold").apply { setSpan(StyleSpan(0), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

        val isolated = text.bidiIsolate().withUiDirection() as Spanned

        val span = isolated.getSpans(0, isolated.length, StyleSpan::class.java).single()
        assertEquals(2, isolated.getSpanStart(span))
        assertEquals(isolated.toString(), isolated.neutralizeDirectionOverrides().toString())
    }
}
