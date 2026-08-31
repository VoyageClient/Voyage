/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.BulletSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.StyleSpan
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ParagraphLayoutSpanTest {

    private fun spanned(vararg spans: Any) = SpannableStringBuilder("pasted text").apply {
        spans.forEach { setSpan(it, 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    @Test
    fun `indenting spans are removed`() {
        val text = spanned(BulletSpan(), QuoteSpan(), LeadingMarginSpan.Standard(48))

        text.removeParagraphLayoutSpans() shouldBeEqualTo true
        text.getSpans(0, text.length, Any::class.java).size shouldBeEqualTo 0
    }

    @Test
    fun `alignment spans are removed`() {
        val text = spanned(AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE))

        text.removeParagraphLayoutSpans() shouldBeEqualTo true
        text.getSpans(0, text.length, Any::class.java).size shouldBeEqualTo 0
    }

    @Test
    fun `character styling is left alone`() {
        val bold = StyleSpan(Typeface.BOLD)
        val text = spanned(bold, BulletSpan())

        text.removeParagraphLayoutSpans() shouldBeEqualTo true
        text.getSpans(0, text.length, Any::class.java).toList() shouldBeEqualTo listOf(bold)
    }

    @Test
    fun `plain text reports nothing removed`() {
        val text = spanned()

        text.removeParagraphLayoutSpans() shouldBeEqualTo false
    }
}
