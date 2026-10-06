/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.text.Spanned
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.room.send.LiteralMentionSpan
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LiteralMentionsTest {

    private fun literalRanges(text: CharSequence): List<String> {
        val spanned = text as? Spanned ?: return emptyList()
        return spanned.getSpans(0, spanned.length, LiteralMentionSpan::class.java)
                .sortedBy { spanned.getSpanStart(it) }
                .map { spanned.subSequence(spanned.getSpanStart(it), spanned.getSpanEnd(it)).toString() }
    }

    @Test
    fun `escaped mentions lose their backslash and are marked literal`() {
        val sent = "hi \\@room and \\@alice:example.org, \\#room:example.org".pillifyRemainingMentions { null }

        sent.toString() shouldBeEqualTo "hi @room and @alice:example.org, #room:example.org"
        literalRanges(sent) shouldBeEqualTo listOf("@room", "@alice:example.org", "#room:example.org")
    }

    @Test
    fun `an unescaped mention is not marked literal`() {
        literalRanges("hi @room".pillifyRemainingMentions { null }) shouldBeEqualTo emptyList()
    }
}
