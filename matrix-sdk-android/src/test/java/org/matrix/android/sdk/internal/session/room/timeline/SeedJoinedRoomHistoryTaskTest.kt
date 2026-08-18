/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.RoomSummaryEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.session.room.summary.SqlRoomSummaryUpdater
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.robolectric.RobolectricTestRunner

private const val A_ROOM_ID = "!room:example.org"
private const val PREV_TOKEN = "t42-prev"
private const val LIVE_TOKEN = "t99-live"
private const val JOIN_TS = 5_000L

@RunWith(RobolectricTestRunner::class)
internal class SeedJoinedRoomHistoryTaskTest {

    private val db = FakeSessionDatabase()
    private val stores = db.stores
    private val roomAPI = mockk<RoomAPI>()
    private val paginationTask = mockk<PaginationTask>()
    private val roomSummaryUpdater = mockk<SqlRoomSummaryUpdater>()
    private val paginationParams = slot<PaginationTask.Params>()

    private val task = DefaultSeedJoinedRoomHistoryTask(
            roomAPI = roomAPI,
            paginationTask = paginationTask,
            globalErrorReceiver = mockk(relaxed = true),
            database = db.database,
            sessionDbDispatcher = db.dispatcher,
            stores = stores,
            roomSummaryUpdater = roomSummaryUpdater,
    )

    init {
        coEvery { paginationTask.execute(capture(paginationParams)) } returns mockk()
        every { roomSummaryUpdater.refreshLatestPreviewableEvent(any(), any(), any(), any()) } just Runs
        coEvery { roomAPI.getRoomMessagesFrom(any(), any(), any(), any(), any()) } returns
                mockk { every { start } returns LIVE_TOKEN }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun givenAJoinedRoom(lastActivityTime: Long? = JOIN_TS) {
        stores.roomSummary.upsert(RoomSummaryEntity(roomId = A_ROOM_ID).apply { this.lastActivityTime = lastActivityTime })
    }

    private fun givenALiveChunk(prevToken: String?, reachedRoomStart: Boolean = false) =
            stores.chunk.insert(A_ROOM_ID, prevToken, null, null, null, isLastForward = true, isLastBackward = reachedRoomStart, null, false)

    private fun givenAMessageIsAlreadyKnown() {
        val dbId = stores.event.insert(
                EventEntity(eventId = "\$message", roomId = A_ROOM_ID, type = EventType.MESSAGE, sender = "@bob:example.org", originServerTs = 1L)
        )
        stores.timelineEvent.insert(TimelineEventEntity(eventId = "\$message", roomId = A_ROOM_ID, displayIndex = 0), chunkId = 1L, rootEventDbId = dbId)
        val summary = stores.roomSummary.get(A_ROOM_ID) ?: RoomSummaryEntity(roomId = A_ROOM_ID)
        summary.latestPreviewableEvent = stores.timelineEvent.getByRoomAndEventId(A_ROOM_ID, "\$message")
        stores.roomSummary.upsert(summary)
    }

    private suspend fun seed() = task.execute(SeedJoinedRoomHistoryTask.Params(A_ROOM_ID))

    @Test
    fun `a room which already has something to show is left alone`() = runTest {
        givenAJoinedRoom()
        givenALiveChunk(PREV_TOKEN)
        givenAMessageIsAlreadyKnown()

        seed()

        coVerify(exactly = 0) { paginationTask.execute(any()) }
        coVerify(exactly = 0) { roomAPI.getRoomMessagesFrom(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an empty room is paginated back from the token sync left`() = runTest {
        givenAJoinedRoom()
        val chunkId = givenALiveChunk(PREV_TOKEN)

        seed()

        paginationParams.captured.roomId shouldBeEqualTo A_ROOM_ID
        paginationParams.captured.from shouldBeEqualTo PREV_TOKEN
        paginationParams.captured.direction shouldBeEqualTo PaginationDirection.BACKWARDS
        paginationParams.captured.originChunkId shouldBeEqualTo chunkId
        coVerify(exactly = 0) { roomAPI.getRoomMessagesFrom(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a room with no chunk at all asks the server where the live edge is`() = runTest {
        givenAJoinedRoom()

        seed()

        paginationParams.captured.from shouldBeEqualTo LIVE_TOKEN
        // The chunk the fetched page is linked to is anchored at that same token.
        stores.chunk.lastForward(A_ROOM_ID)!!.prev_token shouldBeEqualTo LIVE_TOKEN
    }

    @Test
    fun `a live chunk left without a token by an unlimited sync falls back to the live edge`() = runTest {
        givenAJoinedRoom()
        val chunkId = givenALiveChunk(prevToken = null)

        seed()

        paginationParams.captured.from shouldBeEqualTo LIVE_TOKEN
        paginationParams.captured.originChunkId shouldBeEqualTo chunkId
    }

    @Test
    fun `a room whose history we already hold in full is left alone`() = runTest {
        givenAJoinedRoom()
        givenALiveChunk(PREV_TOKEN, reachedRoomStart = true)

        seed()

        coVerify(exactly = 0) { paginationTask.execute(any()) }
    }

    @Test
    fun `history older than the join does not drop the room back down the list`() = runTest {
        givenAJoinedRoom(lastActivityTime = JOIN_TS)
        givenALiveChunk(PREV_TOKEN)
        // Pagination brought in a message from long before we joined.
        every { roomSummaryUpdater.refreshLatestPreviewableEvent(any(), any(), any(), any()) } answers {
            val summary = stores.roomSummary.get(A_ROOM_ID)!!
            summary.lastActivityTime = 1_000L
            stores.roomSummary.upsert(summary)
        }

        seed()

        stores.roomSummary.get(A_ROOM_ID)!!.lastActivityTime shouldBeEqualTo JOIN_TS
    }

    @Test
    fun `a room whose history is newer than the join keeps the newer date`() = runTest {
        givenAJoinedRoom(lastActivityTime = JOIN_TS)
        givenALiveChunk(PREV_TOKEN)
        every { roomSummaryUpdater.refreshLatestPreviewableEvent(any(), any(), any(), any()) } answers {
            val summary = stores.roomSummary.get(A_ROOM_ID)!!
            summary.lastActivityTime = JOIN_TS + 1_000L
            stores.roomSummary.upsert(summary)
        }

        seed()

        stores.roomSummary.get(A_ROOM_ID)!!.lastActivityTime shouldBeEqualTo JOIN_TS + 1_000L
    }
}
