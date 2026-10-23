/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlainSingleLineInputFilterTest {

    private val field = SpannableStringBuilder().apply { filters = arrayOf(PlainSingleLineInputFilter()) }

    private fun paste(text: CharSequence) {
        field.replace(field.length, field.length, text)
    }

    private fun styled(text: String) = SpannableStringBuilder(text).apply {
        setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(TypefaceSpan("monospace"), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    @Test
    fun `formatting from a rich paste is dropped`() {
        paste(styled("hunter2"))
        field.toString() shouldBeEqualTo "hunter2"
        field.getSpans(0, field.length, StyleSpan::class.java).shouldBeEmpty()
        field.getSpans(0, field.length, TypefaceSpan::class.java).shouldBeEmpty()
    }

    @Test
    fun `surrounding line breaks are trimmed`() {
        paste("\n@alice:example.org\r\n")
        field.toString() shouldBeEqualTo "@alice:example.org"
    }

    @Test
    fun `a multi-line paste is rejected`() {
        paste("abc")
        paste("first\nsecond")
        field.toString() shouldBeEqualTo "abc"
    }

    @Test
    fun `non-styling spans survive`() {
        val marker = Any()
        val source = SpannableStringBuilder("typed").apply { setSpan(marker, 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        paste(source)
        field.toString() shouldBeEqualTo "typed"
        field.getSpans(0, field.length, Any::class.java).toList() shouldBeEqualTo listOf(marker)
    }

    @Test
    fun `plain text passes untouched`() {
        paste("plain")
        field.toString() shouldBeEqualTo "plain"
    }
}
