/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.room.send.LiteralMentionSpan
import org.matrix.android.sdk.api.session.room.send.MatrixItemSpan
import org.matrix.android.sdk.api.util.MatrixItem
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
    fun `an escaped pill sends as its plain id`() {
        val text = SpannableStringBuilder("see \\#coreboot and \\Bob ").apply {
            setSpan(pill(MatrixItem.RoomAliasItem("#coreboot:matrix.org", "#coreboot")), 5, 14, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(pill(MatrixItem.RoomItem("!abc:example.org", "Room")), 20, 23, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val sent = text.pillifyRemainingMentions { null } as Spanned

        sent.toString() shouldBeEqualTo "see #coreboot:matrix.org and !abc:example.org "
        sent.getSpans(0, sent.length, MatrixItemSpan::class.java).size shouldBeEqualTo 0
        literalRanges(sent) shouldBeEqualTo listOf("#coreboot:matrix.org", "!abc:example.org")
    }

    @Test
    fun `an escaped room-id pill prefers the canonical alias`() {
        val text = SpannableStringBuilder("\\Room").apply {
            setSpan(pill(MatrixItem.RoomItem("!abc:example.org", "Room")), 1, 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        text.pillifyRemainingMentions { MatrixItem.RoomAliasItem("#room:example.org") }.toString() shouldBeEqualTo "#room:example.org"
    }

    private fun pill(item: MatrixItem) = object : MatrixItemSpan {
        override val matrixItem = item
    }

    @Test
    fun `an unescaped mention is not marked literal`() {
        literalRanges("hi @room".pillifyRemainingMentions { null }) shouldBeEqualTo emptyList()
    }

    @Test
    fun `a restored mention against the next word is only pilled once separated from it`() {
        fun scan(text: String, restoredLength: Int) = SpannableStringBuilder(text).let {
            it.setSpan(RestoredMentionSpan(), 0, restoredLength, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
            findMentions(it, requireTerminator = true).map { range -> text.substring(range.first, range.last + 1) }
        }
        scan("@alice:example.orword ", "@alice:example.or".length) shouldBeEqualTo emptyList()
        scan("@alice:example.org word ", "@alice:example.org ".length) shouldBeEqualTo listOf("@alice:example.org")
    }
}
