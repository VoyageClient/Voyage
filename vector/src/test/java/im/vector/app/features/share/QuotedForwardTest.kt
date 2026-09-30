/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.share

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class QuotedForwardTest {

    private val forwarded = mapOf("event_id" to "\$e", "room_id" to "!r", "sender" to "@a:b")

    @Test
    fun `plain text is quoted and loses the forward metadata`() {
        val result = quoteForwardContent(
                "m.room.message",
                mapOf("msgtype" to "m.text", "body" to "hi <there>\n\nbye", "m.forwarded" to forwarded, "com.famedly.app.forwarded" to forwarded)
        )

        result shouldBeEqualTo mapOf(
                "msgtype" to "m.text",
                "body" to "> hi <there>\n>\n> bye",
                "format" to "org.matrix.custom.html",
                "formatted_body" to "<blockquote>hi &lt;there&gt;<br /><br />bye</blockquote>"
        )
    }

    @Test
    fun `html is wrapped as-is`() {
        val result = quoteForwardContent(
                "m.room.message",
                mapOf("msgtype" to "m.notice", "body" to "**x**", "format" to "org.matrix.custom.html", "formatted_body" to "<strong>x</strong>")
        )

        result["body"] shouldBeEqualTo "> **x**"
        result["formatted_body"] shouldBeEqualTo "<blockquote><strong>x</strong></blockquote>"
    }

    @Test
    fun `a reply fallback is dropped before quoting`() {
        val result = quoteForwardContent(
                "m.room.message",
                mapOf(
                        "msgtype" to "m.text",
                        "body" to "> <@a:b> original\n\nanswer",
                        "format" to "org.matrix.custom.html",
                        "formatted_body" to "<mx-reply><blockquote>original</blockquote></mx-reply>answer"
                )
        )

        result["body"] shouldBeEqualTo "> answer"
        result["formatted_body"] shouldBeEqualTo "<blockquote>answer</blockquote>"
    }

    @Test
    fun `a leading quote without a reply is kept`() {
        val result = quoteForwardContent("m.room.message", mapOf("msgtype" to "m.text", "body" to "> said\n\nreply"))

        result["body"] shouldBeEqualTo "> > said\n>\n> reply"
    }

    @Test
    fun `an emote becomes quoted text`() {
        val result = quoteForwardContent("m.room.message", mapOf("msgtype" to "m.emote", "body" to "waves"))

        result["msgtype"] shouldBeEqualTo "m.text"
        result["body"] shouldBeEqualTo "> * waves"
        result["formatted_body"] shouldBeEqualTo "<blockquote>* waves</blockquote>"
    }

    @Test
    fun `a media caption is quoted`() {
        val result = quoteForwardContent(
                "m.room.message",
                mapOf("msgtype" to "m.image", "body" to "look", "filename" to "a.png", "url" to "mxc://x/y")
        )

        result["body"] shouldBeEqualTo "> look"
        result["filename"] shouldBeEqualTo "a.png"
        result["url"] shouldBeEqualTo "mxc://x/y"
    }

    @Test
    fun `an upload whose body is its filename is not quoted`() {
        val padded = mapOf("msgtype" to "m.file", "body" to "report.pdf ", "filename" to "report.pdf", "url" to "mxc://x/y")
        val legacyReply = mapOf("msgtype" to "m.file", "body" to "> <@a:b> hi\n\nreport.pdf", "filename" to "report.pdf", "url" to "mxc://x/y")
        val htmlReply = legacyReply + mapOf(
                "format" to "org.matrix.custom.html",
                "formatted_body" to "<mx-reply><blockquote>hi</blockquote></mx-reply>report.pdf"
        )

        quoteForwardContent("m.room.message", padded) shouldBeEqualTo padded
        quoteForwardContent("m.room.message", legacyReply) shouldBeEqualTo legacyReply
        quoteForwardContent("m.room.message", htmlReply) shouldBeEqualTo htmlReply
    }

    @Test
    fun `uncaptioned media and stickers are only stripped`() {
        val image = mapOf("msgtype" to "m.image", "body" to "a.png", "url" to "mxc://x/y")
        val sticker = mapOf("body" to "cat", "url" to "mxc://x/z")

        quoteForwardContent("m.room.message", image + ("m.forwarded" to forwarded)) shouldBeEqualTo image
        quoteForwardContent("m.sticker", sticker + ("m.forwarded" to forwarded)) shouldBeEqualTo sticker
    }
}
