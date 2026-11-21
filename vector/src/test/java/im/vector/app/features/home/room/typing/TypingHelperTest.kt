/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.typing

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import im.vector.app.core.resources.StringProvider
import im.vector.lib.strings.CommonStrings
import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TypingHelperTest {

    private val templates = mapOf(
            CommonStrings.typing_indicator_one to "%s is typing…",
            CommonStrings.typing_indicator_two to "%1\$s and %2\$s are typing…",
            CommonStrings.typing_indicator_three to "%1\$s, %2\$s, and %3\$s are typing…",
            CommonStrings.typing_indicator_many to "Many people are typing…",
    )

    private val stringProvider = mockk<StringProvider> {
        every { getString(any()) } answers { templates.getValue(firstArg()) }
        every { getString(any(), *anyVararg()) } answers {
            @Suppress("UNCHECKED_CAST")
            templates.getValue(firstArg()).format(*(args[1] as Array<Any?>))
        }
    }

    private val helper = TypingHelper(stringProvider)

    @Test
    fun `picks the template by count`() {
        helper.getTypingLine(emptyList()).plain() shouldBeEqualTo ""
        helper.getTypingLine(names("Alice")).plain() shouldBeEqualTo "Alice is typing…"
        helper.getTypingLine(names("Alice", "Bob")).plain() shouldBeEqualTo "Alice and Bob are typing…"
        helper.getTypingLine(names("Alice", "Bob", "Carol")).plain() shouldBeEqualTo "Alice, Bob, and Carol are typing…"
        helper.getTypingLine(names("Alice", "Bob", "Carol", "Dan")).plain() shouldBeEqualTo "Many people are typing…"
        helper.getTypingLine(names("Alice", "Bob", "Carol", "Dan", "Eve")).plain() shouldBeEqualTo "Many people are typing…"
    }

    @Test
    fun `names keep their spans in place`() {
        val line = helper.getTypingLine(names("Alice", "Alice")) as Spanned

        val spans = line.getSpans(0, line.length, StyleSpan::class.java).sortedBy { line.getSpanStart(it) }
        spans.size shouldBeEqualTo 2
        spans.forEach { line.subSequence(line.getSpanStart(it), line.getSpanEnd(it)).toString() shouldBeEqualTo "Alice" }
        line.plain() shouldBeEqualTo "Alice and Alice are typing…"
    }

    @Test
    fun `names that look like placeholders are inserted verbatim`() {
        val sentinel = Char(0xE000).toString()
        helper.getTypingLine(names("%s", sentinel)).plain() shouldBeEqualTo "%s and $sentinel are typing…"
    }

    private fun names(vararg names: String): List<CharSequence> = names.map { name ->
        SpannableString(name).apply { setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    private fun CharSequence.plain(): String = toString().filterNot { it in BIDI_CONTROLS }

    private companion object {
        private val BIDI_CONTROLS = listOf(0x2066, 0x2067, 0x2068, 0x2069, 0x200E, 0x200F).map { Char(it) }
    }
}
