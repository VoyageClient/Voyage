/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.model.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.LocalEcho

class ForwardedInfoTest {

    private val origin = mapOf(
            "event_id" to "\$event",
            "room_id" to "!room:example.org",
            "sender" to "@alice:example.org",
            "origin_server_ts" to 1234L
    )

    private fun event(
            eventId: String = "\$event",
            type: String = EventType.MESSAGE,
            content: Map<String, Any> = mapOf("msgtype" to "m.text", "body" to "hello")
    ) = Event(
            type = type,
            eventId = eventId,
            roomId = "!room:example.org",
            senderId = "@alice:example.org",
            originServerTs = 1234L,
            content = content
    )

    private fun Map<String, Any?>.forwarded(): Map<*, *> = this[ForwardedInfo.STABLE_KEY] as Map<*, *>

    @Test
    fun `a message is forwarded with its origin and content`() {
        val payload = event().toForwardPayload()

        assertEquals(origin + ("content" to mapOf("msgtype" to "m.text", "body" to "hello")), payload.forwarded())
    }

    @Test
    fun `the latest edit replaces the content`() {
        val payload = event().toForwardPayload(editedContent = mapOf("msgtype" to "m.text", "body" to "edited"))

        assertEquals(mapOf("msgtype" to "m.text", "body" to "edited"), payload.forwarded()["content"])
    }

    @Test
    fun `without a source link the sender and timestamp are carried`() {
        val payload = event().toForwardPayload(includeSourceLink = false)

        assertEquals((origin - "room_id" - "event_id") + ("content" to mapOf("msgtype" to "m.text", "body" to "hello")), payload.forwarded())
    }

    @Test
    fun `a local echo has no event id to point at`() {
        val payload = event(eventId = LocalEcho.createLocalEchoId()).toForwardPayload()

        assertFalse(payload.forwarded().containsKey("event_id"))
        assertEquals("@alice:example.org", payload.forwarded()["sender"])
    }

    @Test
    fun `forwarding a forward copies its m forwarded unchanged`() {
        val original = mapOf(
                "event_id" to "\$origin",
                "sender" to "@bob:example.org",
                "custom" to "kept",
                "content" to mapOf("msgtype" to "m.text", "body" to "original")
        )
        val payload = event(content = mapOf("msgtype" to "m.text", "body" to "fallback", ForwardedInfo.UNSTABLE_KEY to original))
                .toForwardPayload(includeSourceLink = false)

        assertEquals(original, payload.forwarded())
    }

    @Test
    fun `forwarding a forward without content keeps its origin and adds the content`() {
        val origin = mapOf("event_id" to "\$origin", "sender" to "@bob:example.org")
        val payload = event(content = mapOf("msgtype" to "m.text", "body" to "hi", ForwardedInfo.STABLE_KEY to origin)).toForwardPayload()

        assertEquals(origin + ("content" to mapOf("msgtype" to "m.text", "body" to "hi")), payload.forwarded())
    }

    @Test
    fun `the reply fallback is stripped from the forwarded content`() {
        val reply = mapOf(
                "msgtype" to "m.text",
                "body" to "> <@bob:example.org> question\n\nanswer",
                "format" to MessageFormat.FORMAT_MATRIX_HTML,
                "formatted_body" to "<mx-reply><blockquote>question</blockquote></mx-reply>answer",
                "m.relates_to" to mapOf("m.in_reply_to" to mapOf("event_id" to "\$question"))
        )
        val content = event(content = reply).toForwardPayload().forwarded()["content"] as Map<*, *>

        assertEquals("answer", content["body"])
        assertEquals("answer", content["formatted_body"])
        assertEquals(reply["m.relates_to"], content["m.relates_to"])
    }

    @Test
    fun `a sticker carries its origin and content`() {
        val payload = event(type = EventType.STICKER, content = mapOf("body" to "cat", "url" to "mxc://x/y")).toForwardPayload()

        assertEquals(origin + ("content" to mapOf("body" to "cat", "url" to "mxc://x/y")), payload.forwarded())
    }

    @Test
    fun `the stable key is read before the unstable one`() {
        val content = mapOf(
                ForwardedInfo.UNSTABLE_KEY to mapOf("sender" to "@unstable:example.org"),
                ForwardedInfo.STABLE_KEY to mapOf("sender" to "@stable:example.org"),
        )
        assertEquals("@stable:example.org", content.getForwardedInfo()?.sender)
        assertEquals("@unstable:example.org", (content - ForwardedInfo.STABLE_KEY).getForwardedInfo()?.sender)
    }

    @Test
    fun `MSC2723 forwards are read`() {
        assertEquals("@legacy:example.org", mapOf(ForwardedInfo.LEGACY_KEY to mapOf("sender" to "@legacy:example.org")).getForwardedInfo()?.sender)
    }

    @Test
    fun `display content is the original with the forward's own relation`() {
        val ownRelation = mapOf("m.in_reply_to" to mapOf("event_id" to "\$mine"))
        val content = mapOf(
                "msgtype" to "m.text",
                "body" to "Forwarded message\nhi",
                "m.relates_to" to ownRelation,
                ForwardedInfo.UNSTABLE_KEY to mapOf(
                        "content" to mapOf(
                                "msgtype" to "m.emote",
                                "body" to "waves",
                                "m.relates_to" to mapOf("m.in_reply_to" to mapOf("event_id" to "\$theirs")),
                                "m.mentions" to mapOf("user_ids" to listOf("@carol:example.org"))
                        )
                )
        )

        assertEquals(mapOf("msgtype" to "m.emote", "body" to "waves", "m.relates_to" to ownRelation), content.forwardedDisplayContent())
    }

    @Test
    fun `sticker display uses the original content and its own mentions`() {
        val sticker = mapOf("body" to "cat", "url" to "mxc://x/y", "info" to emptyMap<String, Any>())
        val content = mapOf(
                "body" to "fallback",
                "m.mentions" to emptyMap<String, Any>(),
                ForwardedInfo.UNSTABLE_KEY to mapOf("content" to sticker),
        )

        assertEquals(sticker + ("m.mentions" to emptyMap<String, Any>()), content.forwardedDisplayContent(EventType.STICKER))
        assertNull(content.forwardedDisplayContent(EventType.MESSAGE))
        assertNull((content + (ForwardedInfo.UNSTABLE_KEY to mapOf("content" to (sticker - "info"))))
                .forwardedDisplayContent(EventType.STICKER))
    }

    @Test
    fun `forwarding a sticker forward preserves its original object`() {
        val sticker = mapOf("body" to "cat", "url" to "mxc://x/y", "info" to emptyMap<String, Any>())
        val forwarded = mapOf("sender" to "@bob:example.org", "custom" to "kept", "content" to sticker)
        val payload = event(type = EventType.STICKER, content = sticker + (ForwardedInfo.UNSTABLE_KEY to forwarded)).toForwardPayload()

        assertEquals(forwarded, payload.forwarded())
    }

    @Test
    fun `legacy forwards are sent using MSC4553 keys`() {
        val payload = event(content = mapOf("msgtype" to "m.text", "body" to "hi", ForwardedInfo.LEGACY_KEY to origin)).toForwardPayload()

        assertEquals(origin + ("content" to mapOf("msgtype" to "m.text", "body" to "hi")), payload.forwarded())
        assertFalse(payload.containsKey(ForwardedInfo.LEGACY_KEY))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `other event types cannot be forwarded`() {
        event(type = EventType.REACTION).toForwardPayload()
    }

    @Test
    fun `invalid or missing forwarded content renders the top level`() {
        assertNull(mapOf(ForwardedInfo.STABLE_KEY to mapOf("sender" to "@a:b")).forwardedDisplayContent())
        assertNull(mapOf(ForwardedInfo.STABLE_KEY to mapOf("content" to mapOf("body" to "no msgtype"))).forwardedDisplayContent())
        assertNull(mapOf("msgtype" to "m.text", "body" to "plain").forwardedDisplayContent())
    }
}
