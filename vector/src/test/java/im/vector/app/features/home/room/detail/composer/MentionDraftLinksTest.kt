/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.text.Spannable
import android.text.SpannableStringBuilder
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.permalinks.PermalinkData
import org.matrix.android.sdk.api.session.permalinks.PermalinkParser
import org.matrix.android.sdk.api.session.room.send.MatrixItemSpan
import org.matrix.android.sdk.api.util.MatrixItem
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URLDecoder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MentionDraftLinksTest {

    private class Pill(override val matrixItem: MatrixItem, override val bodyText: String?) : MatrixItemSpan

    private fun draftOf(vararg parts: Any): String {
        val text = SpannableStringBuilder()
        parts.forEach { part ->
            if (part is Pill) {
                text.append("￼")
                text.setSpan(part, text.length - 1, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                text.append(part.toString())
            }
        }
        return serializeMentionPills(text)
    }

    private fun mentionsIn(draft: String) = MENTION_MARKDOWN_LINK.findAll(draft).map { match ->
        match.mentionLabel to URLDecoder.decode(match.mentionPermalink.removePrefix("https://matrix.to/#/"), "UTF-8")
    }.toList()

    private val bob = MatrixItem.UserItem("@bob:example.org", "Bob")

    @Test
    fun `any display name survives the draft round trip`() {
        listOf(
                "Bob [mod]",
                "[[[",
                "]]]",
                "\\",
                "a\\]b\\[c\\\\",
                "](https://matrix.to/#/@mallory:example.org)",
                "x](${Char(0x2060)}https://matrix.to/#/@mallory:example.org)",
                "*_~`<>|#!()",
                "two\nlines",
                "${Char(0x202E)}reversed${Char(0x202C)} 🙂",
        ).forEach { name ->
            mentionsIn(draftOf("hey ", Pill(bob, name), " there")) shouldBeEqualTo listOf(name to bob.id)
        }
    }

    @Test
    fun `text written around a pill does not join its label`() {
        val draft = draftOf("see [x and \\", Pill(bob, "Bob"), "] (y)")
        mentionsIn(draft) shouldBeEqualTo listOf("Bob" to bob.id)
        val match = MENTION_MARKDOWN_LINK.find(draft)!!
        draft.substring(0, match.range.first).unguardBeforeMentionLink().toString() shouldBeEqualTo "see [x and \\"
    }

    @Test
    fun `an authored matrix_to link next to a pill stays text`() {
        val draft = draftOf("[Bob](https://matrix.to/#/@bob:example.org) ", Pill(bob, "Bob [mod]"))
        mentionsIn(draft) shouldBeEqualTo listOf("Bob [mod]" to bob.id)
    }

    @Test
    fun `an id holding link or permalink syntax survives the draft round trip`() {
        val odd = MatrixItem.UserItem("@a)b/c?d+e%f(g:example.org", "Odd")
        mentionsIn(draftOf(Pill(odd, "Odd"))) shouldBeEqualTo listOf("Odd" to odd.id)
    }

    @Test
    fun `a pill without body text is written under its name`() {
        mentionsIn(draftOf(Pill(bob, " "))) shouldBeEqualTo listOf("Bob" to bob.id)
    }

    @Test
    fun `a round-tripped room mention still parses as one`() {
        val room = MatrixItem.RoomAliasItem("#room:example.org", "My [Room]")
        val match = MENTION_MARKDOWN_LINK.find(draftOf(Pill(room, null)))!!
        match.mentionLabel shouldBeEqualTo "My [Room]"
        (PermalinkParser.parse(match.mentionPermalink) as PermalinkData.RoomLink).roomIdOrAlias shouldBeEqualTo room.id
    }

    @Test
    fun `an edit prefill with a bracketed name parses back to the name`() {
        val prefill = spliceMentionLinks("Bob [mod]: hi", """<a href="https://matrix.to/#/@bob:example.org">Bob [mod]</a>: hi""")
        mentionsIn(prefill) shouldBeEqualTo listOf("Bob [mod]" to bob.id)
    }
}
