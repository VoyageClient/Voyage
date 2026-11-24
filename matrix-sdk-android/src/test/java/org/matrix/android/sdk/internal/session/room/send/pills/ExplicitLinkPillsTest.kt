/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send.pills

import android.text.Spannable
import android.text.SpannableStringBuilder
import org.commonmark.ext.explicitlinks.ExplicitLinksExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.maths.MathsExtension
import org.commonmark.ext.subsupstrike.SubSupStrikeExtension
import org.commonmark.ext.underline.UnderlineExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.room.send.MatrixItemSpan
import org.matrix.android.sdk.api.util.MatrixItem
import org.matrix.android.sdk.internal.session.room.send.IntentionalMentions
import org.matrix.android.sdk.internal.session.room.send.MarkdownParser
import org.robolectric.RobolectricTestRunner

/** Every kind of pill the composer makes, sent as a mention, and never nested inside a markdown link. */
@RunWith(RobolectricTestRunner::class)
class ExplicitLinkPillsTest {

    private val extensions = listOf(
            MathsExtension.create(),
            TablesExtension.create(),
            SubSupStrikeExtension.create(),
            UnderlineExtension.create(),
            ExplicitLinksExtension.create()
    )

    private val parser = MarkdownParser(
            Parser.builder().extensions(extensions).build(),
            Parser.builder().extensions(extensions).build(),
            HtmlRenderer.builder().extensions(extensions).softbreak("<br />").build(),
            AndroidTextPillsUtils(MentionLinkSpecComparator()),
    )

    private class Pill(override val matrixItem: MatrixItem, override val bodyText: String) : MatrixItemSpan

    private val items = listOf(
            MatrixItem.UserItem("@alice:example.org", "Alice"),
            MatrixItem.RoomAliasItem("#room:example.org", "Room alias"),
            MatrixItem.RoomItem("!room:example.org", "Room"),
            MatrixItem.SpaceItem("!space:example.org", "Space"),
    )

    /** [before] + a pill of [item] written as [shown] + [after]. */
    private fun send(before: String, item: MatrixItem, shown: String, after: String): String? {
        val text = SpannableStringBuilder(before).append(shown).append(after)
        text.setSpan(Pill(item, shown), before.length, before.length + shown.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return parser.parse(text).formattedText
    }

    @Test
    fun `a pill of any kind is a plain mention anchor with a matrix URI`() {
        val hrefs = listOf(
                "matrix:u/alice:example.org",
                "matrix:r/room:example.org",
                "matrix:roomid/room:example.org",
                "matrix:roomid/space:example.org",
        )
        items.zip(hrefs).forEach { (item, href) ->
            assertEquals(
                    """hi <a href="$href">${item.id}</a> there""",
                    send("hi ", item, item.id, " there")
            )
        }
    }

    @Test
    fun `a pill without markdown is a mention anchor too`() {
        val text = SpannableStringBuilder("hi Alice")
        text.setSpan(Pill(items[0], "Alice"), 3, 8, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        assertEquals(
                """hi <a href="matrix:u/alice:example.org">Alice</a>""",
                AndroidTextPillsUtils(MentionLinkSpecComparator()).processSpecialSpansToHtml(text)
        )
    }

    @Test
    fun `a pill whose id has no matrix URI is sent as its text`() {
        val text = SpannableStringBuilder("hi <Message>")
        text.setSpan(Pill(MatrixItem.EventItem("\$event:example.org", "<Message>"), "<Message>"), 3, 12, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        assertEquals("hi &lt;Message&gt;", AndroidTextPillsUtils(MentionLinkSpecComparator()).processSpecialSpansToHtml(text))
        assertEquals(null, send("hi ", MatrixItem.EventItem("\$event:example.org", "Message"), "Message", ""))
    }

    @Test
    fun `a pill's matrix URI still yields its mention`() {
        val html = send("hi ", items[0], "Alice", "")
        assertEquals(listOf("@alice:example.org"), IntentionalMentions.build("hi Alice", html)?.userIds)
    }

    @Test
    fun `a pill in a link target stays the target the user typed`() {
        items.forEach { item ->
            listOf("" to "", "<" to ">").forEach { (open, close) ->
                assertEquals(
                        """Questions? <a href="${item.id}" data-mx-link data-org.matrix.msc4550.link>DM me</a>""",
                        send("Questions? [DM me]($open", item, item.id, "$close)")
                )
            }
        }
    }

    @Test
    fun `a pill in a link's text is link text, not a nested mention`() {
        items.forEach { item ->
            val html = send("[ask ", item, item.id, " here](https://example.org)")
            assertEquals(
                    """<a href="https://example.org" data-mx-link data-org.matrix.msc4550.link>ask ${item.id} here</a>""",
                    html
            )
            assertEquals(null, IntentionalMentions.build("x", html)?.userIds)
        }
    }

    @Test
    fun `a pill after a finished link is still a mention`() {
        assertEquals(
                """<a href="https://example.org" data-mx-link data-org.matrix.msc4550.link>x</a> """ +
                        """<a href="matrix:u/alice:example.org">Alice</a>""",
                send("[x](https://example.org) ", items[0], "Alice", "")
        )
    }
}
