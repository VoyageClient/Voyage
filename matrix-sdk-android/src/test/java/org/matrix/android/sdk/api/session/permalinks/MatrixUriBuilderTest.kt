/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.permalinks

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class MatrixUriBuilderTest {

    private val domainlessRoomId = "!Q8wzLKa3vTnR2bXcPmE7uYdJf0sHgWiN5oV1kZ9tA6c"
    private val eventId = "\$Xb3nH7qLpR2wKs9vTgYcM4eJd0aFz6NiUoB1lE8xS5y"

    @Test
    fun `each entity gets its MSC2312 type and drops its sigil`() {
        MatrixUriBuilder.build("@alice:example.org") shouldBeEqualTo "matrix:u/alice:example.org"
        MatrixUriBuilder.build("#room:example.org") shouldBeEqualTo "matrix:r/room:example.org"
        MatrixUriBuilder.build("!room:example.org") shouldBeEqualTo "matrix:roomid/room:example.org"
        MatrixUriBuilder.build(domainlessRoomId) shouldBeEqualTo "matrix:roomid/${domainlessRoomId.drop(1)}"
    }

    @Test
    fun `an event link nests the event under the room, with via servers last`() {
        MatrixUriBuilder.build("#room:example.org", "\$event:example.org") shouldBeEqualTo "matrix:r/room:example.org/e/event:example.org"
        MatrixUriBuilder.build(domainlessRoomId, eventId, listOf("example.org", "example.com", "example.org")) shouldBeEqualTo
                "matrix:roomid/${domainlessRoomId.drop(1)}/e/${eventId.drop(1)}?via=example.org&via=example.com"
    }

    @Test
    fun `characters outside a path segment are percent-encoded`() {
        MatrixUriBuilder.build("#a/b?c#d (e)&\"é\":example.org") shouldBeEqualTo "matrix:r/a%2Fb%3Fc%23d%20%28e%29%26%22%C3%A9%22:example.org"
        MatrixUriBuilder.build("!room:example.org", "\$event", listOf("[::1]:8448")) shouldBeEqualTo
                "matrix:roomid/room:example.org/e/event?via=%5B::1%5D:8448"
    }

    @Test
    fun `ids without a matrix URI form give null`() {
        MatrixUriBuilder.build("+group:example.org") shouldBeEqualTo null
        MatrixUriBuilder.build("@") shouldBeEqualTo null
        MatrixUriBuilder.build("") shouldBeEqualTo null
        MatrixUriBuilder.build("@alice:example.org", eventId) shouldBeEqualTo null
        MatrixUriBuilder.mentionLink("+group:example.org") shouldBeEqualTo "https://matrix.to/#/+group:example.org"
    }

    @Test
    fun `what is built parses back to the same target`() {
        val user = PermalinkParser.parse(MatrixUriBuilder.mentionLink("@a/lice:example.org")) as PermalinkData.UserLink
        user.userId shouldBeEqualTo "@a/lice:example.org"
        val alias = PermalinkParser.parse(MatrixUriBuilder.mentionLink("#r+o=om:example.org")) as PermalinkData.RoomLink
        alias.roomIdOrAlias shouldBeEqualTo "#r+o=om:example.org"
        val event = PermalinkParser.parse(MatrixUriBuilder.build(domainlessRoomId, eventId, listOf("example.org", "example.com"))!!) as PermalinkData.RoomLink
        event.roomIdOrAlias shouldBeEqualTo domainlessRoomId
        event.eventId shouldBeEqualTo eventId
        event.viaParameters shouldBeEqualTo listOf("example.org", "example.com")
    }
}
