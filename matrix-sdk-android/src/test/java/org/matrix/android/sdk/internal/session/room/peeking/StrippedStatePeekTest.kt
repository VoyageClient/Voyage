/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.peeking

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.session.room.model.RoomType
import org.matrix.android.sdk.internal.auth.version.Versions
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.session.homeserver.CapabilitiesAPI
import org.matrix.android.sdk.internal.session.room.RoomAPI

class StrippedStatePeekTest {

    private fun response(json: String) =
            MoshiProvider.providesMoshi().adapter(StrippedStateResponse::class.java).fromJson(json)!!

    private val spaceResponse = """
        {
          "via": "remote.example",
          "num_joined_members": 7,
          "state": [
            {"type": "m.room.create", "state_key": "", "sender": "@a:remote.example", "content": {"type": "m.space"}},
            {"type": "m.room.name", "state_key": "", "sender": "@a:remote.example", "content": {"name": "Lounge"}},
            {"type": "m.room.topic", "state_key": "", "sender": "@a:remote.example", "content": {"topic": "Chat"}},
            {"type": "m.room.avatar", "state_key": "", "sender": "@a:remote.example", "content": {"url": "mxc://remote.example/a"}},
            {"type": "m.room.join_rules", "state_key": "", "sender": "@a:remote.example", "content": {"join_rule": "knock"}},
            {"type": "m.room.canonical_alias", "state_key": "", "sender": "@a:remote.example", "content": {"alt_aliases": ["#alt:remote.example"]}},
            {"type": "m.room.history_visibility", "state_key": "", "sender": "@a:remote.example", "content": {"history_visibility": "world_readable"}}
          ]
        }
    """.trimIndent()

    @Test
    fun `maps stripped state to a preview credited to the remote server`() {
        val peek = response(spaceResponse).toPeekResult(
                roomId = "!room:remote.example",
                requestedAlias = null,
                viaServers = listOf("other.example", "remote.example"),
                ownServerName = "home.example",
        )

        peek.name shouldBeEqualTo "Lounge"
        peek.topic shouldBeEqualTo "Chat"
        peek.avatarUrl shouldBeEqualTo "mxc://remote.example/a"
        peek.roomType shouldBeEqualTo RoomType.SPACE
        peek.joinRule shouldBeEqualTo RoomJoinRules.KNOCK
        peek.isPublic shouldBeEqualTo false
        peek.alias shouldBeEqualTo "#alt:remote.example"
        peek.numJoinedMembers shouldBeEqualTo 7
        peek.worldReadable shouldBeEqualTo true
        peek.providedByServer shouldBeEqualTo "remote.example"
        peek.viaServers shouldBeEqualTo listOf("remote.example", "other.example")
    }

    @Test
    fun `a preview from our own server is not credited and keeps the requested alias`() {
        val peek = response("""{"via": "home.example", "state": []}""").toPeekResult(
                roomId = "!room:remote.example",
                requestedAlias = "#room:remote.example",
                viaServers = emptyList(),
                ownServerName = "home.example",
        )

        peek.providedByServer.shouldBeNull()
        peek.alias shouldBeEqualTo "#room:remote.example"
        peek.numJoinedMembers.shouldBeNull()
        peek.worldReadable shouldBeEqualTo false
        peek.viaServers shouldBeEqualTo emptyList()
    }

    private fun task(advertised: Boolean, roomAPI: RoomAPI): DefaultGetRoomStrippedStateTask {
        val capabilitiesAPI = mockk<CapabilitiesAPI> {
            coEvery { getVersions() } returns Versions(
                    supportedVersions = listOf("v1.11"),
                    unstableFeatures = mapOf("org.continuwuity.msc4560" to advertised),
            )
        }
        return DefaultGetRoomStrippedStateTask(roomAPI, StrippedStateSupport(capabilitiesAPI), mockk(relaxed = true))
    }

    private val params = GetRoomStrippedStateTask.Params("!room:remote.example", listOf("a", "a", "b"), listOf("m.room.name"), true)

    @Test
    fun `returns null without a request when the server does not advertise MSC4560`() = runBlocking {
        val roomAPI = mockk<RoomAPI>()
        task(advertised = false, roomAPI).execute(params).shouldBeNull()
        coVerify(exactly = 0) { roomAPI.getStrippedState(any(), any(), any()) }
        coVerify(exactly = 0) { roomAPI.getStrippedStateUnstable(any(), any(), any()) }
    }

    @Test
    fun `falls back to the unstable endpoint and stops trying the stable one`() = runBlocking {
        val unrecognized = Failure.ServerError(MatrixError(MatrixError.M_UNRECOGNIZED, "Unrecognized request"), 404)
        val roomAPI = mockk<RoomAPI> {
            coEvery { getStrippedState(any(), any(), any()) } throws unrecognized
            coEvery { getStrippedStateUnstable(any(), any(), any()) } returns StrippedStateResponse(via = "remote.example")
        }
        val task = task(advertised = true, roomAPI)

        task.execute(params)?.via shouldBeEqualTo "remote.example"
        task.execute(params)?.via shouldBeEqualTo "remote.example"

        coVerify(exactly = 1) { roomAPI.getStrippedState(any(), any(), any()) }
        coVerify(exactly = 2) {
            roomAPI.getStrippedStateUnstable("!room:remote.example", listOf("a", "b"), StrippedStateBody(listOf("m.room.name"), true))
        }
    }
}
