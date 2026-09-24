/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.membership

import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.internal.database.model.RoomMemberSummaryEntity
import org.matrix.android.sdk.internal.database.sql.store.SessionStores

private const val A_ROOM = "!room:example.org"

class AmbiguousDisplayNameCacheTest {

    private var members = emptyList<RoomMemberSummaryEntity>()
    private var memberQueries = 0

    private val stores: SessionStores = mockk(relaxed = true) {
        every { roomMember.getByRoom(A_ROOM) } answers { memberQueries++; members }
    }

    private val cache = AmbiguousDisplayNameCache(stores)

    private fun member(userId: String, displayName: String?) =
            RoomMemberSummaryEntity(primaryKey = "$A_ROOM-$userId", userId = userId, roomId = A_ROOM, displayName = displayName)

    @Test
    fun `a name only one member wears is not ambiguous`() {
        members = listOf(member("@alice:example.org", "Alice"), member("@bob:example.org", "Bob"))

        cache.isAmbiguous(A_ROOM, "Alice") shouldBe false
    }

    @Test
    fun `a name two members share is ambiguous`() {
        members = listOf(member("@alice:example.org", "Alice"), member("@alice:other.org", "Alice"))

        cache.isAmbiguous(A_ROOM, "Alice") shouldBe true
    }

    /** Members load lazily; claiming uniqueness before they arrive is what dropped the suffix. */
    @Test
    fun `an unloaded room answers unknown`() {
        members = emptyList()

        cache.isAmbiguous(A_ROOM, "Alice") shouldBe null
    }

    @Test
    fun `no name is never ambiguous`() {
        members = listOf(member("@alice:example.org", null))

        cache.isAmbiguous(A_ROOM, null) shouldBe false
        cache.isAmbiguous(A_ROOM, "") shouldBe false
    }

    @Test
    fun `a namesake joining makes the name ambiguous`() {
        members = listOf(member("@alice:example.org", "Alice"))
        cache.isAmbiguous(A_ROOM, "Alice") shouldBe false

        members = members + member("@alice:other.org", "Alice")
        cache.invalidate(A_ROOM)

        cache.isAmbiguous(A_ROOM, "Alice") shouldBe true
    }

    /** The mapper's fingerprint keys off the generation, so a rebuild has to be able to see the change. */
    @Test
    fun `invalidating a loaded room moves the generation`() {
        members = listOf(member("@alice:example.org", "Alice"))
        cache.isAmbiguous(A_ROOM, "Alice")
        val before = cache.generation

        cache.invalidate(A_ROOM)

        (cache.generation > before) shouldBeEqualTo true
    }

    @Test
    fun `invalidating a room that was never loaded changes nothing`() {
        val before = cache.generation

        cache.invalidate(A_ROOM)

        cache.generation shouldBeEqualTo before
    }

    /** Re-querying per event cost seconds of a cold room open, since every event asks about its sender. */
    @Test
    fun `an unloaded room is queried once, not once per ask`() {
        members = emptyList()

        repeat(50) { cache.isAmbiguous(A_ROOM, "Alice") shouldBe null }

        memberQueries shouldBeEqualTo 1
    }

    @Test
    fun `members arriving after an unloaded read are picked up`() {
        members = emptyList()
        cache.isAmbiguous(A_ROOM, "Alice") shouldBe null
        val before = cache.generation

        members = listOf(member("@alice:example.org", "Alice"), member("@alice:other.org", "Alice"))
        cache.invalidate(A_ROOM)

        cache.isAmbiguous(A_ROOM, "Alice") shouldBe true
        (cache.generation > before) shouldBeEqualTo true
    }
}
