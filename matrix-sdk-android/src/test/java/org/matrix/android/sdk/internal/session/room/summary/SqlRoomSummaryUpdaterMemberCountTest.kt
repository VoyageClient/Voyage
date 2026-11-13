/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.summary

import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.sync.model.RoomSyncSummary
import org.matrix.android.sdk.internal.database.model.RoomEntity
import org.matrix.android.sdk.internal.database.model.RoomMemberSummaryEntity
import org.matrix.android.sdk.internal.database.model.RoomMembersLoadStatusType
import org.matrix.android.sdk.internal.session.room.membership.RoomName
import org.matrix.android.sdk.test.fakes.FakeClock
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.robolectric.RobolectricTestRunner

private const val A_ROOM_ID = "!room:example.org"
private const val MY_USER_ID = "@alice:example.org"

@RunWith(RobolectricTestRunner::class)
internal class SqlRoomSummaryUpdaterMemberCountTest {

    private val db = FakeSessionDatabase()
    private val stores = db.stores

    private val updater = SqlRoomSummaryUpdater(
            userId = MY_USER_ID,
            roomDisplayNameResolver = mockk { every { resolve(any(), any()) } returns RoomName("Room", "room") },
            roomAvatarResolver = mockk { every { resolve(any(), any()) } returns null },
            roomAccountDataDataSource = mockk { every { getAccountDataEvent(any(), any()) } returns null },
            roomSummaryEventDecryptor = mockk(relaxed = true),
            roomSummaryEventsHelper = mockk {
                every { getLatestPreviewableEvent(any(), any(), any()) } returns null
                every { getLatestUnreadEvent(any(), any()) } returns null
            },
            directRoomsCache = DirectRoomsCache(),
            lightweightSettingsStorage = mockk { every { areThreadMessagesEnabled() } returns true },
            clock = FakeClock().apply { givenEpoch(1_000L) },
    )

    @After
    fun tearDown() {
        db.close()
    }

    private fun givenMembers(vararg userIds: String, membership: Membership = Membership.JOIN) {
        userIds.forEach { userId ->
            stores.roomMember.upsert(
                    RoomMemberSummaryEntity(primaryKey = "${A_ROOM_ID}_$userId", userId = userId, roomId = A_ROOM_ID)
                            .also { it.membership = membership }
            )
        }
    }

    private fun givenMembersLoadStatus(status: RoomMembersLoadStatusType) {
        stores.room.upsert(RoomEntity(roomId = A_ROOM_ID).also {
            it.membership = Membership.JOIN
            it.membersLoadStatus = status
        })
    }

    private fun update(summary: RoomSyncSummary? = null) =
            updater.update(stores, A_ROOM_ID, Membership.JOIN, roomSummary = summary, updateMembers = true)

    private fun counts() = stores.roomSummary.get(A_ROOM_ID)!!.let { it.joinedMembersCount to it.invitedMembersCount }

    @Test
    fun `the server's counts are kept when a batch only brings a lazily loaded member`() {
        givenMembers(MY_USER_ID)
        update(RoomSyncSummary(joinedMembersCount = 40, invitedMembersCount = 2))
        givenMembers("@bob:example.org")

        update()

        counts() shouldBeEqualTo (40 to 2)
    }

    @Test
    fun `local members raise the counts past a stale server value`() {
        givenMembers(MY_USER_ID)
        update(RoomSyncSummary(joinedMembersCount = 1, invitedMembersCount = 0))
        givenMembers("@bob:example.org", "@carol:example.org")
        givenMembers("@dan:example.org", membership = Membership.INVITE)

        update()

        counts() shouldBeEqualTo (3 to 1)
    }

    @Test
    fun `a fully loaded member list replaces the counts outright`() {
        givenMembers(MY_USER_ID)
        update(RoomSyncSummary(joinedMembersCount = 40, invitedMembersCount = 2))
        givenMembers("@bob:example.org")
        givenMembersLoadStatus(RoomMembersLoadStatusType.LOADED)

        update()

        counts() shouldBeEqualTo (2 to 0)
    }

    @Test
    fun `counts the server sends in the batch win`() {
        givenMembers(MY_USER_ID, "@bob:example.org")
        givenMembersLoadStatus(RoomMembersLoadStatusType.LOADED)

        update(RoomSyncSummary(joinedMembersCount = 7, invitedMembersCount = 3))

        counts() shouldBeEqualTo (7 to 3)
    }
}
