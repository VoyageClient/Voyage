/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.model.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.EventType

/** The expected fallbacks are the MSC4553 examples, verbatim, except that a forwarded emote is styled as one. */
class ForwardContentBuilderTest {

    private val alice = "https://matrix.to/#/%40alice%3Aexample.org"

    private fun origin(eventId: String) = mapOf(
            "event_id" to eventId,
            "room_id" to "!source:example.org",
            "sender" to "@alice:example.org",
            "origin_server_ts" to 1722451200000L,
    )

    private fun link(eventId: String) =
            "https://matrix.to/#/!source%3Aexample.org/${ForwardContentBuilder.encodeComponent(eventId)}?via=example.org"

    private fun build(forwarded: Map<String, Any>, silent: Boolean = false, displayName: String? = null) = ForwardContentBuilder.build(
            eventType = EventType.MESSAGE,
            payload = mapOf(ForwardedInfo.STABLE_KEY to forwarded),
            silent = silent,
            senderDisplayName = displayName,
            viaServers = listOf("example.org"),
    )

    private fun keys(forwarded: Map<String, Any>) = mapOf(
            ForwardedInfo.STABLE_KEY to forwarded,
            ForwardedInfo.UNSTABLE_KEY to forwarded,
            "m.mentions" to emptyMap<String, Any>(),
    )

    @Test
    fun `forwarded text message`() {
        val content = mapOf(
                "msgtype" to "m.text",
                "body" to "Meeting starts at noon.",
                "format" to "org.matrix.custom.html",
                "formatted_body" to "<p>Meeting starts at noon.</p>"
        )
        val forwarded = origin("\$original:example.org") + ("content" to content)

        val expected = mapOf(
                "msgtype" to "m.text",
                "body" to "Forwarded from @alice:example.org - view original message: ${link("\$original:example.org")}\nMeeting starts at noon.",
                "format" to "org.matrix.custom.html",
                "formatted_body" to "<strong>Forwarded from <a href=\"$alice\">@alice:example.org</a> - " +
                        "<a href=\"${link("\$original:example.org")}\">view original message</a></strong>" +
                        "<blockquote><p>Meeting starts at noon.</p></blockquote>",
        ) + keys(forwarded)
        assertEquals(expected, build(forwarded))
        assertEquals("https://matrix.to/#/!source%3Aexample.org/%24original%3Aexample.org?via=example.org", link("\$original:example.org"))
    }

    @Test
    fun `forwarded text message with only content`() {
        val content = mapOf(
                "msgtype" to "m.text",
                "body" to "Meeting starts at noon.",
                "format" to "org.matrix.custom.html",
                "formatted_body" to "<p>Meeting starts at noon.</p>"
        )
        val forwarded = mapOf("content" to content)

        val expected = mapOf(
                "msgtype" to "m.text",
                "body" to "Forwarded message\nMeeting starts at noon.",
                "format" to "org.matrix.custom.html",
                "formatted_body" to "<strong>Forwarded message</strong><blockquote><p>Meeting starts at noon.</p></blockquote>",
        ) + keys(forwarded)
        assertEquals(expected, build(forwarded))
    }

    @Test
    fun `forwarded emote`() {
        val forwarded = origin("\$emote:example.org") + ("content" to mapOf("msgtype" to "m.emote", "body" to "waves"))

        val expected = mapOf(
                "msgtype" to "m.text",
                "body" to "Forwarded from @alice:example.org - view original message: ${link("\$emote:example.org")}\nAlice waves",
                "format" to "org.matrix.custom.html",
                "formatted_body" to "<strong>Forwarded from <a href=\"$alice\">@alice:example.org</a> - " +
                        "<a href=\"${link("\$emote:example.org")}\">view original message</a></strong>" +
                        "<blockquote><em><strong><a href=\"$alice\">Alice</a></strong> waves</em></blockquote>",
        ) + keys(forwarded)
        assertEquals(expected, build(forwarded, displayName = "Alice"))
    }

    @Test
    fun `forwarded emote with no original sender metadata`() {
        val forwarded = mapOf("content" to mapOf("msgtype" to "m.emote", "body" to "waves"))

        val expected = mapOf(
                "msgtype" to "m.text",
                "body" to "Forwarded message\nSender waves",
                "format" to "org.matrix.custom.html",
                "formatted_body" to "<strong>Forwarded message</strong><blockquote><em><strong>Sender</strong> waves</em></blockquote>",
        ) + keys(forwarded)
        assertEquals(expected, build(forwarded))
    }

    @Test
    fun `forwarded file without a caption`() {
        val forwarded = origin("\$file:example.org") +
                ("content" to mapOf("msgtype" to "m.file", "body" to "agenda.pdf", "url" to "mxc://example.org/agenda"))

        val expected = mapOf(
                "msgtype" to "m.file",
                "filename" to "agenda.pdf",
                "body" to "Forwarded from @alice:example.org - view original message: ${link("\$file:example.org")}",
                "format" to "org.matrix.custom.html",
                "formatted_body" to "<strong>Forwarded from <a href=\"$alice\">@alice:example.org</a> - " +
                        "<a href=\"${link("\$file:example.org")}\">view original message</a></strong>",
                "url" to "mxc://example.org/agenda",
        ) + keys(forwarded)
        assertEquals(expected, build(forwarded))
    }

    @Test
    fun `a media caption is quoted`() {
        val forwarded = mapOf(
                "content" to mapOf("msgtype" to "m.image", "body" to "look <here>", "filename" to "cat.png", "url" to "mxc://x/y")
        )

        val result = build(forwarded)

        assertEquals("cat.png", result["filename"])
        assertEquals("Forwarded message\nlook <here>", result["body"])
        assertEquals("<strong>Forwarded message</strong><blockquote>look &lt;here&gt;</blockquote>", result["formatted_body"])
    }

    @Test
    fun `a location keeps its own body`() {
        val location = mapOf("msgtype" to "m.location", "body" to "Big Ben", "geo_uri" to "geo:51.5,-0.1")
        val forwarded = origin("\$loc:example.org") + ("content" to location)

        assertEquals(location + keys(forwarded), build(forwarded))
    }

    @Test
    fun `a silent forward says nothing about the origin`() {
        val content = mapOf("msgtype" to "m.text", "body" to "hi")
        val result = build(origin("\$original:example.org") + ("content" to content), silent = true)

        assertEquals(mapOf("content" to content), result[ForwardedInfo.STABLE_KEY])
        assertEquals("Forwarded message\nhi", result["body"])
    }

    @Test
    fun `the forward's own relation and mentions are not copied from the original`() {
        val content = mapOf(
                "msgtype" to "m.text",
                "body" to "hi",
                "m.relates_to" to mapOf("m.in_reply_to" to mapOf("event_id" to "\$x")),
                "m.mentions" to mapOf("user_ids" to listOf("@bob:example.org"))
        )
        val result = build(mapOf("content" to content))

        assertFalse(result.containsKey("m.relates_to"))
        assertEquals(emptyMap<String, Any>(), result["m.mentions"])
    }

    @Test
    fun `a sticker carries its origin without content`() {
        val result = ForwardContentBuilder.build(
                eventType = EventType.STICKER,
                payload = mapOf("body" to "cat", "url" to "mxc://x/y", ForwardedInfo.STABLE_KEY to origin("\$s:example.org")),
        )

        assertEquals(
                mapOf(
                        "body" to "cat",
                        "url" to "mxc://x/y",
                        ForwardedInfo.STABLE_KEY to origin("\$s:example.org"),
                        ForwardedInfo.UNSTABLE_KEY to origin("\$s:example.org"),
                        "m.mentions" to emptyMap<String, Any>(),
                ),
                result
        )
    }

    @Test
    fun `whole numbers parsed as doubles are sent as integers`() {
        val forwarded = mapOf(
                "origin_server_ts" to 1722451200000.0,
                "content" to mapOf("msgtype" to "m.image", "body" to "a.png", "info" to mapOf("w" to 1080.0))
        )
        val result = build(forwarded)

        assertEquals(1722451200000L, (result[ForwardedInfo.STABLE_KEY] as Map<*, *>)["origin_server_ts"])
        assertEquals(1080L, ((result["info"]) as Map<*, *>)["w"])
    }

    @Test
    fun `an oversized forward drops the stable copy, then the quote`() {
        // Quoted in body and formatted_body, and carried in each forwarded copy.
        val long = "x".repeat(13_000)
        val big = build(mapOf("content" to mapOf("msgtype" to "m.text", "body" to long)))
        assertFalse(big.containsKey(ForwardedInfo.STABLE_KEY))
        assertTrue(big.containsKey(ForwardedInfo.UNSTABLE_KEY))
        assertEquals("Forwarded message\n$long", big["body"])

        val huge = build(mapOf("content" to mapOf("msgtype" to "m.text", "body" to "x".repeat(30_000))))
        assertEquals("Forwarded message", huge["body"])
    }
}
