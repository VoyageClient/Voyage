/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.html

import android.text.SpannableString
import android.text.Spanned
import android.widget.TextView
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EmoteLineSpacingTest {

    private fun textView() = TextView(RuntimeEnvironment.getApplication()).apply {
        setLineSpacing(STYLE_EXTRA, 1f)
    }

    private fun emoteLines(): SpannableString {
        val text = SpannableString("￼\n￼")
        listOf(0, 2).forEach {
            text.setSpan(EmoteImageSpan("blob", "mxc://server/blob", null, null), it, it + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return text
    }

    @Test
    fun `emote text drops the line spacing`() {
        val tv = textView()
        tv.applyEmoteLineSpacing(emoteLines())
        tv.lineSpacingExtra shouldBeEqualTo 0f
    }

    @Test
    fun `plain text keeps the style line spacing`() {
        val tv = textView()
        tv.applyEmoteLineSpacing("hello\nworld")
        tv.lineSpacingExtra shouldBeEqualTo STYLE_EXTRA
    }

    @Test
    fun `a recycled view restores the style line spacing after an emote message`() {
        val tv = textView()
        tv.applyEmoteLineSpacing(emoteLines())
        tv.applyEmoteLineSpacing("hello")
        tv.lineSpacingExtra shouldBeEqualTo STYLE_EXTRA
    }

    private companion object {
        const val STYLE_EXTRA = 4f
    }
}
