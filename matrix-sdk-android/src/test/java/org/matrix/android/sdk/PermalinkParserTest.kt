/*
 * Copyright 2021 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk

import org.junit.Assert
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import org.matrix.android.sdk.api.session.permalinks.MatrixToConverter
import org.matrix.android.sdk.api.session.permalinks.PermalinkData
import org.matrix.android.sdk.api.session.permalinks.PermalinkParser

@FixMethodOrder(MethodSorters.JVM)
class PermalinkParserTest {

    @Test
    fun testParseEmailInvite() {
        val rawInvite = """
            https://app.element.io/#/room/%21MRBNLPtFnMAazZVPMO%3Amatrix.org?email=bob%2Bspace%40example.com&signurl=https%3A%2F%2Fvector.im%2F_matrix%2Fidentity%2Fapi%2Fv1%2Fsign-ed25519%3Ftoken%3DXmOwRZnSFabCRhTywFbJWKXWVNPysOpXIbroMGaUymqkJSvHeVKRsjHajwjCYdBsvGSvHauxbKfJmOxtXldtyLnyBMLKpBQCMzyYggrdapbVIceWZBtmslOQrXLABRoe%26private_key%3DT2gq0c3kJB_8OroXVxl1pBnzHsN7V6Xn4bEBSeW1ep4&room_name=Team2&room_avatar_url=&inviter_name=hiphop5&guest_access_token=&guest_user_id=
        """.trimIndent()
                .replace("https://app.element.io/#/room/", "https://matrix.to/#/")

        val parsedLink = PermalinkParser.parse(rawInvite)
        Assert.assertTrue("Should be parsed as email invite but was ${parsedLink::class.java}", parsedLink is PermalinkData.RoomEmailInviteLink)
        parsedLink as PermalinkData.RoomEmailInviteLink
        Assert.assertEquals("!MRBNLPtFnMAazZVPMO:matrix.org", parsedLink.roomId)
        Assert.assertEquals(
                "XmOwRZnSFabCRhTywFbJWKXWVNPysOpXIbroMGaUymqkJSvHeVKRsjHajwjCYdBsvGSvHauxbKfJmOxtXldtyLnyBMLKpBQCMzyYggrdapbVIceWZBtmslOQrXLABRoe",
                parsedLink.token
        )
        Assert.assertEquals("vector.im", parsedLink.identityServer)
        Assert.assertEquals("Team2", parsedLink.roomName)
        Assert.assertEquals("hiphop5", parsedLink.inviterName)
    }

    @Test
    fun testParseLinkWIthEvent() {
        val rawInvite =
                "https://matrix.to/#/!OGEhHVWSdvArJzumhm:matrix.org/\$xuvJUVDJnwEeVjPx029rAOZ50difpmU_5gZk_T0jGfc" +
                        "?via=matrix.org&via=libera.chat&via=matrix.example.io"

        val parsedLink = PermalinkParser.parse(rawInvite)
        Assert.assertTrue("Should be parsed as room link", parsedLink is PermalinkData.RoomLink)
        parsedLink as PermalinkData.RoomLink
        Assert.assertEquals("!OGEhHVWSdvArJzumhm:matrix.org", parsedLink.roomIdOrAlias)
        Assert.assertEquals("\$xuvJUVDJnwEeVjPx029rAOZ50difpmU_5gZk_T0jGfc", parsedLink.eventId)
        Assert.assertEquals(3, parsedLink.viaParameters.size)
        Assert.assertTrue(parsedLink.viaParameters.contains("matrix.example.io"))
        Assert.assertTrue(parsedLink.viaParameters.contains("matrix.org"))
        Assert.assertTrue(parsedLink.viaParameters.contains("matrix.example.io"))
    }

    @Test
    fun testParseMatrixUriUser() {
        val parsedLink = PermalinkParser.parse("matrix:u/alice%3Aexample.org")
        Assert.assertTrue("Should be parsed as user link but was ${parsedLink::class.java}", parsedLink is PermalinkData.UserLink)
        parsedLink as PermalinkData.UserLink
        Assert.assertEquals("@alice:example.org", parsedLink.userId)
    }

    @Test
    fun testParseMatrixUriRoomAliasWithEvent() {
        val parsedLink = PermalinkParser.parse("matrix:r/room:example.org/e/xuvJUVDJnwEeVjPx029rAOZ50difpmU_5gZk_T0jGfc?via=example.org")
        Assert.assertTrue("Should be parsed as room link but was ${parsedLink::class.java}", parsedLink is PermalinkData.RoomLink)
        parsedLink as PermalinkData.RoomLink
        Assert.assertEquals("#room:example.org", parsedLink.roomIdOrAlias)
        Assert.assertTrue(parsedLink.isRoomAlias)
        Assert.assertEquals("\$xuvJUVDJnwEeVjPx029rAOZ50difpmU_5gZk_T0jGfc", parsedLink.eventId)
        Assert.assertEquals(listOf("example.org"), parsedLink.viaParameters)
    }

    @Test
    fun testParseMatrixUriRoomIdWithAuthority() {
        val parsedLink = PermalinkParser.parse("matrix://roomid/OGEhHVWSdvArJzumhm:matrix.org")
        Assert.assertTrue("Should be parsed as room link but was ${parsedLink::class.java}", parsedLink is PermalinkData.RoomLink)
        parsedLink as PermalinkData.RoomLink
        Assert.assertEquals("!OGEhHVWSdvArJzumhm:matrix.org", parsedLink.roomIdOrAlias)
        Assert.assertFalse(parsedLink.isRoomAlias)
    }

    @Test
    fun testParseMatrixUriWithAuthorityAndFragment() {
        val parsedLink = PermalinkParser.parse("matrix://example.org/u/alice:example.org#anchor")
        Assert.assertTrue("Should be parsed as user link but was ${parsedLink::class.java}", parsedLink is PermalinkData.UserLink)
        Assert.assertEquals("@alice:example.org", (parsedLink as PermalinkData.UserLink).userId)
    }

    @Test
    fun testParseMatrixUriDeprecatedQualifiers() {
        val parsedLink = PermalinkParser.parse("matrix:room/room:example.org/event/abcdef")
        Assert.assertTrue("Should be parsed as room link but was ${parsedLink::class.java}", parsedLink is PermalinkData.RoomLink)
        parsedLink as PermalinkData.RoomLink
        Assert.assertEquals("#room:example.org", parsedLink.roomIdOrAlias)
        Assert.assertEquals("\$abcdef", parsedLink.eventId)
    }

    @Test
    fun testParseMatrixUriChatAction() {
        val parsedLink = PermalinkParser.parse("matrix:u/alice:example.org?action=chat")
        Assert.assertTrue("Should be parsed as user link but was ${parsedLink::class.java}", parsedLink is PermalinkData.UserLink)
        Assert.assertTrue((parsedLink as PermalinkData.UserLink).startChat)
        Assert.assertFalse((PermalinkParser.parse("matrix:u/alice:example.org") as PermalinkData.UserLink).startChat)
    }

    @Test
    fun testParseMalformedMatrixUri() {
        Assert.assertTrue(PermalinkParser.parse("matrix:u") is PermalinkData.FallbackLink)
        Assert.assertTrue(PermalinkParser.parse("matrix:x/alice:example.org") is PermalinkData.FallbackLink)
        Assert.assertTrue(PermalinkParser.parse("matrix:r/room:example.org/x/event") is PermalinkData.FallbackLink)
        // An event only lives under a room
        Assert.assertTrue(PermalinkParser.parse("matrix:u/alice:example.org/e/abcdef") is PermalinkData.FallbackLink)
    }

    @Test
    fun testParseMatrixUriDomainlessRoomIdWithEvent() {
        val roomId = "Q8wzLKa3vTnR2bXcPmE7uYdJf0sHgWiN5oV1kZ9tA6c"
        val eventId = "Xb3nH7qLpR2wKs9vTgYcM4eJd0aFz6NiUoB1lE8xS5y"
        val parsedLink = PermalinkParser.parse("matrix:roomid/$roomId/e/$eventId?via=example.org&via=example.com")
        Assert.assertTrue("Should be parsed as room link but was ${parsedLink::class.java}", parsedLink is PermalinkData.RoomLink)
        parsedLink as PermalinkData.RoomLink
        Assert.assertEquals("!$roomId", parsedLink.roomIdOrAlias)
        Assert.assertEquals("\$$eventId", parsedLink.eventId)
        Assert.assertEquals(listOf("example.org", "example.com"), parsedLink.viaParameters)
    }

    @Test
    fun testParseMatrixToLayoutOnOtherHost() {
        val user = PermalinkParser.parse("https://link.example.org/#/@alice:example.org")
        Assert.assertEquals("@alice:example.org", (user as PermalinkData.UserLink).userId)

        val alias = PermalinkParser.parse("https://example.org/chat/#/%23room%3Aexample.org")
        Assert.assertEquals("#room:example.org", (alias as PermalinkData.RoomLink).roomIdOrAlias)

        val event = PermalinkParser.parse("https://link.example.org/#/!room:example.org/\$event:example.org?via=example.org")
        event as PermalinkData.RoomLink
        Assert.assertEquals("!room:example.org", event.roomIdOrAlias)
        Assert.assertEquals("\$event:example.org", event.eventId)
        Assert.assertEquals(listOf("example.org"), event.viaParameters)
    }

    @Test
    fun testWebPermalinkNeedsAMatrixId() {
        Assert.assertTrue(MatrixToConverter.isWebPermalink("https://link.example.org/#/@alice:example.org"))
        Assert.assertTrue(MatrixToConverter.isWebPermalink("http://chat.example.org/#/room/#room:example.org"))
        Assert.assertTrue(MatrixToConverter.isWebPermalink("https://chat.example.org/#/user/@alice:example.org"))
        Assert.assertFalse(MatrixToConverter.isWebPermalink("https://example.org/#/settings"))
        Assert.assertFalse(MatrixToConverter.isWebPermalink("https://example.org/#/@alice"))
        Assert.assertFalse(MatrixToConverter.isWebPermalink("https://example.org/#/%E0%A4%A"))
        Assert.assertFalse(MatrixToConverter.isWebPermalink("https://example.org/@alice:example.org"))
        Assert.assertFalse(MatrixToConverter.isWebPermalink("ftp://example.org/#/@alice:example.org"))
        Assert.assertTrue(PermalinkParser.parse("https://example.org/#/settings") is PermalinkData.FallbackLink)
    }
}
