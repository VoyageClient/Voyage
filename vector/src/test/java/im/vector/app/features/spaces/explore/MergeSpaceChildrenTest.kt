/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.spaces.explore

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.room.model.SpaceChildInfo

class MergeSpaceChildrenTest {

    private fun child(roomId: String, name: String? = null) = SpaceChildInfo(
            childRoomId = roomId,
            isKnown = true,
            roomType = null,
            name = name,
            topic = null,
            avatarUrl = null,
            order = null,
            activeMemberCount = null,
            viaServers = emptyList(),
            parentRoomId = "!space:example.org",
            suggested = null,
            canonicalAlias = null,
            aliases = null,
            worldReadable = false
    )

    @Test
    fun `children the hierarchy response left out are taken from the local ones`() {
        val merged = mergeSpaceChildren(
                apiChildren = listOf(child("!a:example.org")),
                localChildren = listOf(child("!b:example.org"))
        )

        merged.map { it.childRoomId } shouldBeEqualTo listOf("!a:example.org", "!b:example.org")
    }

    @Test
    fun `a room known to both sides is listed once, with the hierarchy version`() {
        val merged = mergeSpaceChildren(
                apiChildren = listOf(child("!a:example.org", name = "From hierarchy")),
                localChildren = listOf(child("!a:example.org", name = "From sync"))
        )

        merged.size shouldBeEqualTo 1
        merged.first().name shouldBeEqualTo "From hierarchy"
    }

    @Test
    fun `an empty hierarchy response falls back entirely to the synced children`() {
        val merged = mergeSpaceChildren(
                apiChildren = emptyList(),
                localChildren = listOf(child("!a:example.org"), child("!b:example.org"))
        )

        merged.map { it.childRoomId } shouldBeEqualTo listOf("!a:example.org", "!b:example.org")
    }

    @Test
    fun `nothing on either side stays empty, so the empty state still shows`() {
        mergeSpaceChildren(apiChildren = emptyList(), localChildren = emptyList()) shouldBeEqualTo emptyList()
    }
}
