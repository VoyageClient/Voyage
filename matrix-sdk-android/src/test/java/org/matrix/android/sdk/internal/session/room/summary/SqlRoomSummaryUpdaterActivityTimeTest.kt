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
import org.amshove.kluent.shouldBeNull
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.RoomSummaryEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.session.room.membership.RoomName
import org.matrix.android.sdk.test.fakes.FakeClock
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.robolectric.RobolectricTestRunner

private const val A_ROOM_ID = "!room:example.org"
private const val MY_USER_ID = "@alice:example.org"
private const val NOW = 9_000L
private const val JOIN_TS = 5_000L
private const val MESSAGE_TS = 1_000L

@RunWith(RobolectricTestRunner::class)
internal class SqlRoomSummaryUpdaterActivityTimeTest {

    private val db = FakeSessionDatabase()
    private val stores = db.stores
    private val eventsHelper = mockk<SqlRoomSummaryEventsHelper>()

    private val updater = SqlRoomSummaryUpdater(
            userId = MY_USER_ID,
            roomDisplayNameResolver = mockk { every { resolve(any(), any()) } returns RoomName("Room", "room") },
            roomAvatarResolver = mockk { every { resolve(any(), any()) } returns null },
            roomAccountDataDataSource = mockk { every { getAccountDataEvent(any(), any()) } returns null },
            roomSummaryEventDecryptor = mockk(relaxed = true),
            roomSummaryEventsHelper = eventsHelper,
            clock = FakeClock().apply { givenEpoch(NOW) },
    )

    init {
        every { eventsHelper.getLatestPreviewableEvent(any(), any(), any()) } returns null
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun givenIJoinedAt(ts: Long) {
        stores.event.insert(
                EventEntity(
                        eventId = "\$join",
                        roomId = A_ROOM_ID,
                        type = EventType.STATE_ROOM_MEMBER,
                        stateKey = MY_USER_ID,
                        sender = MY_USER_ID,
                        originServerTs = ts,
                        content = """{"membership":"join"}"""
                )
        )
        stores.currentStateEvent.upsert(A_ROOM_ID, EventType.STATE_ROOM_MEMBER, MY_USER_ID, "\$join", "\$join")
    }

    private fun givenAMessageAt(ts: Long) {
        val dbId = stores.event.insert(
                EventEntity(eventId = "\$message", roomId = A_ROOM_ID, type = EventType.MESSAGE, sender = "@bob:example.org", originServerTs = ts)
        )
        val timelineEvent = TimelineEventEntity(eventId = "\$message", roomId = A_ROOM_ID, displayIndex = 0)
        stores.timelineEvent.insert(timelineEvent, chunkId = 1L, rootEventDbId = dbId)
        every { eventsHelper.getLatestPreviewableEvent(any(), any(), any()) } returns
                stores.timelineEvent.getByRoomAndEventId(A_ROOM_ID, "\$message")
    }

    private fun update(membership: Membership) = updater.update(stores, A_ROOM_ID, membership = membership)

    private fun activityTime() = stores.roomSummary.get(A_ROOM_ID)?.lastActivityTime

    @Test
    fun `a room joined with nothing to show is dated from the join`() {
        givenIJoinedAt(JOIN_TS)

        update(Membership.JOIN)

        activityTime() shouldBeEqualTo JOIN_TS
    }

    @Test
    fun `a join we have no membership event for is dated from now, so it still sorts to the top`() {
        update(Membership.JOIN)

        activityTime() shouldBeEqualTo NOW
    }

    @Test
    fun `a room with a message is dated from the message`() {
        givenIJoinedAt(JOIN_TS)
        givenAMessageAt(MESSAGE_TS)

        update(Membership.JOIN)

        activityTime() shouldBeEqualTo MESSAGE_TS
    }

    @Test
    fun `an invite is left undated, since it is not listed among the joined rooms`() {
        givenIJoinedAt(JOIN_TS)

        update(Membership.INVITE)

        activityTime().shouldBeNull()
    }

    @Test
    fun `a date already known is never overwritten by the join`() {
        stores.roomSummary.upsert(RoomSummaryEntity(roomId = A_ROOM_ID).apply { lastActivityTime = 4_242L })
        givenIJoinedAt(JOIN_TS)

        update(Membership.JOIN)

        activityTime() shouldBeEqualTo 4_242L
    }
}
