/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.home.room.detail.timeline.tools

import android.text.SpannableStringBuilder
import android.text.Spanned
import im.vector.app.features.html.HtmlCodeSpan
import io.noties.markwon.core.MarkwonTheme
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InlineCodeBlocksTest {

    private val theme = MarkwonTheme.builderWithDefaults(RuntimeEnvironment.getApplication()).build()

    private fun textWithCodeBlock(before: String, code: String, after: String): SpannableStringBuilder =
            SpannableStringBuilder(before + code + after).apply {
                setSpan(HtmlCodeSpan(theme, isBlock = true), before.length, before.length + code.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

    private fun Spanned.codeSpans() = getSpans(0, length, HtmlCodeSpan::class.java).toList()

    @Test
    fun `a code block becomes inline code without its edge whitespace`() {
        val text = textWithCodeBlock("before\n", "  val x = 1\n", "after")

        text.inlineCodeBlocks(text.codeSpans())

        val span = text.codeSpans().single()
        span.isBlock.shouldBeFalse()
        text.substring(text.getSpanStart(span), text.getSpanEnd(span)) shouldBeEqualTo "val x = 1"
    }

    @Test
    fun `the preview flattener keeps a multi-line code block as one inline code run`() {
        val flattened = textWithCodeBlock("before\n\n", "line one\nline two\n", "\nafter")
                .flattenBlockFormattingForPreview() as Spanned

        flattened.toString() shouldBeEqualTo "before line one line two after"
        val span = flattened.codeSpans().single()
        span.isBlock.shouldBeFalse()
        flattened.substring(flattened.getSpanStart(span), flattened.getSpanEnd(span)) shouldBeEqualTo "line one line two"
    }

    @Test
    fun `a whitespace-only code block is dropped`() {
        val text = textWithCodeBlock("a", "\n \n", "b")

        text.inlineCodeBlocks(text.codeSpans())

        text.codeSpans() shouldBeEqualTo emptyList()
    }
}
