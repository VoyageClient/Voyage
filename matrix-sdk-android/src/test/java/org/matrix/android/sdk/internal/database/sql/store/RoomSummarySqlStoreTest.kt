/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldContainSame
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.internal.database.model.RoomSummaryEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RoomSummarySqlStoreTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var database: SessionSqlDatabase
    private lateinit var stores: SessionStores
    private lateinit var store: RoomSummarySqlStore

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = SessionSqlDatabase.Schema)
        database = SessionSqlDatabase(driver)
        stores = SessionStores(database)
        store = stores.roomSummary
    }

    @After
    fun tearDown() {
        driver.close()
    }

    private fun summary(
            roomId: String,
            membership: Membership = Membership.JOIN,
            isDirect: Boolean = false,
            directUserId: String? = null,
            invitedMembersCount: Int? = 0,
    ) = RoomSummaryEntity(roomId = roomId).apply {
        this.membership = membership
        this.isDirect = isDirect
        this.directUserId = directUserId
        this.invitedMembersCount = invitedMembersCount
        name = "Room $roomId"
    }

    @Test
    fun `upsert and read back a summary`() {
        store.upsert(summary("!a:hs", membership = Membership.JOIN, isDirect = true, directUserId = "@bob:hs"))

        val read = store.get("!a:hs")!!
        read.roomId shouldBeEqualTo "!a:hs"
        read.membership shouldBeEqualTo Membership.JOIN
        read.isDirect shouldBe true
        read.directUserId shouldBeEqualTo "@bob:hs"
        read.name shouldBeEqualTo "Room !a:hs"
    }

    @Test
    fun `directRooms returns only direct rooms with their user and membership`() {
        store.upsert(summary("!dm:hs", membership = Membership.JOIN, isDirect = true, directUserId = "@bob:hs"))
        store.upsert(summary("!group:hs", membership = Membership.JOIN, isDirect = false))

        store.directRooms() shouldContainSame listOf(Triple("!dm:hs", "@bob:hs", Membership.JOIN.name))
    }

    @Test
    fun `updateDirectInfo flips a plain room into a direct room`() {
        store.ensureExists("!r:hs")

        store.updateDirectInfo("!r:hs", isDirect = true, directUserId = "@carol:hs")

        store.get("!r:hs")!!.let {
            it.isDirect shouldBe true
            it.directUserId shouldBeEqualTo "@carol:hs"
        }
    }

    @Test
    fun `selectByRoomIdAndMembership matches only the requested membership`() {
        store.upsert(summary("!joined:hs", membership = Membership.JOIN))
        store.upsert(summary("!invited:hs", membership = Membership.INVITE))

        database.roomSummaryQueries.selectByRoomIdAndMembership("!joined:hs", Membership.JOIN.name)
                .executeAsOneOrNull() shouldBeEqualTo "!joined:hs"
        database.roomSummaryQueries.selectByRoomIdAndMembership("!joined:hs", Membership.INVITE.name)
                .executeAsOneOrNull().shouldBeNull()
    }

    @Test
    fun `selectByRoomIdAndInvitedCount matches once the invited count is reached`() {
        store.upsert(summary("!r:hs", invitedMembersCount = 0))

        database.roomSummaryQueries.selectByRoomIdAndInvitedCount("!r:hs", 2L).executeAsOneOrNull().shouldBeNull()

        store.upsert(summary("!r:hs", invitedMembersCount = 2))

        database.roomSummaryQueries.selectByRoomIdAndInvitedCount("!r:hs", 2L).executeAsOneOrNull() shouldBeEqualTo "!r:hs"
    }
}
