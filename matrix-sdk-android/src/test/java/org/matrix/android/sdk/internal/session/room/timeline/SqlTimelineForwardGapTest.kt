/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.TimelineSettings
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
internal class SqlTimelineForwardGapTest {

    private val roomId = "!room:example.org"
    private val old = message("\$old", 1L)
    private val latest = message("\$latest", 10L)

    @Test
    fun `a range demoted under a scrolled-up reader pages forward from a fetched token`() = runTest {
        val tokens = mockk<FetchEventTokenTask>()
        coEvery { tokens.execute(any()) } returns "forward-token"
        withDemotedRange(tokens) { db, timeline, pagination, demoted ->
            assertTrue(timeline.hasMoreToLoad(Timeline.Direction.FORWARDS))
            assertFalse(timeline.isLive)

            timeline.paginate(Timeline.Direction.FORWARDS, 50)
            runCurrent()

            coVerify { tokens.execute(FetchEventTokenTask.Params(roomId, old.eventId, PaginationDirection.FORWARDS)) }
            coVerify { pagination.execute(match { it.from == "forward-token" && it.originChunkId == demoted && it.direction == PaginationDirection.FORWARDS }) }
            assertEquals("forward-token", db.stores.chunk.getById(demoted)?.next_token)
        }
    }

    @Test
    fun `a range the server cannot page forward from joins the live edge`() = runTest {
        val tokens = mockk<FetchEventTokenTask>()
        coEvery { tokens.execute(any()) } throws Failure.ServerError(MatrixError(MatrixError.M_NOT_FOUND, "gone"), 404)
        withDemotedRange(tokens) { _, timeline, _, _ ->
            timeline.paginate(Timeline.Direction.FORWARDS, 50)
            runCurrent()

            assertEquals(listOf(latest.eventId), timeline.getSnapshot().map { it.eventId })
            assertTrue(timeline.isLive)
        }
    }

    @Test
    fun `an offline forward token fetch keeps the position and retries later`() = runTest {
        val tokens = mockk<FetchEventTokenTask>()
        coEvery { tokens.execute(any()) } throws Failure.NetworkConnection(IOException("offline"))
        withDemotedRange(tokens) { _, timeline, _, _ ->
            timeline.paginate(Timeline.Direction.FORWARDS, 50)
            runCurrent()

            assertEquals(listOf(old.eventId), timeline.getSnapshot().map { it.eventId })
            assertTrue(timeline.hasMoreToLoad(Timeline.Direction.FORWARDS))
        }
    }

    @Test
    fun `a gappy sync landing between seeding and the first build still opens at the live edge`() = runTest {
        val db = FakeSessionDatabase()
        val oldLive = db.stores.chunk.insert(roomId, "old-prev", null, true, false, null, false)
        insertRow(db, old, oldLive)
        var newLive = -1L
        val chunks = spyk(db.stores.chunk)
        // The sync commits right after the open read the live range, before anything was built from it.
        every { chunks.lastForward(roomId) } answers {
            callOriginal().also {
                if (newLive < 0) {
                    db.stores.chunk.setLastForward(oldLive, false)
                    newLive = db.stores.chunk.insert(roomId, "live-prev", null, true, false, null, false)
                    insertRow(db, latest, newLive)
                }
            }
        }
        val stores = spyk(db.stores)
        every { stores.chunk } returns chunks
        val loader = loader(mapOf(oldLive to listOf(old)))
        every { loader.chunkSnapshotNewest(any(), any()) } answers {
            if (firstArg<Long>() == newLive) listOf(latest) else listOf(old)
        }
        val timeline = timeline(db, loader, mockk(relaxed = true), mockk(relaxed = true), stores)
        try {
            timeline.start(null)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals(listOf(latest.eventId), timeline.getSnapshot().map { it.eventId })
            assertTrue(timeline.isLive)
        } finally {
            timeline.dispose()
            runCurrent()
            db.close()
        }
    }

    private suspend fun TestScope.withDemotedRange(
            tokens: FetchEventTokenTask,
            block: suspend (FakeSessionDatabase, SqlTimeline, PaginationTask, Long) -> Unit,
    ) {
        val db = FakeSessionDatabase()
        val demoted = db.stores.chunk.insert(roomId, "old-prev", null, true, false, null, false)
        insertRow(db, old, demoted)
        val loader = loader(mapOf(demoted to listOf(old)))
        val pagination = mockk<PaginationTask>()
        coEvery { pagination.execute(any()) } returns TokenChunkEventPersistor.Result.REACHED_END
        val timeline = timeline(db, loader, pagination, tokens)
        try {
            timeline.start(null)
            runCurrent()
            timeline.setViewAtLiveEdge(false)
            db.stores.chunk.setLastForward(demoted, false)
            val live = db.stores.chunk.insert(roomId, "live-prev", null, true, false, null, false)
            insertRow(db, latest, live)
            every { loader.chunkSnapshotNewest(live, any()) } returns listOf(latest)
            every { loader.chunkSnapshotAfter(live, any(), any()) } returns emptyList()
            timeline.onNewTimelineEvents(roomId, listOf(latest.eventId))
            runCurrent()
            assertEquals(listOf(old.eventId), timeline.getSnapshot().map { it.eventId })
            block(db, timeline, pagination, demoted)
        } finally {
            timeline.dispose()
            runCurrent()
            db.close()
        }
    }

    private fun loader(events: Map<Long, List<TimelineEvent>>): SqlChunkSnapshotLoader {
        val loader = mockk<SqlChunkSnapshotLoader>()
        every { loader.chunkChangesFlow(any()) } returns emptyFlow()
        every { loader.sendingChangesFlow(roomId) } returns emptyFlow()
        every { loader.ignoredUserIdsFlow() } returns emptyFlow()
        every { loader.annotationSummaryChangesFlow(roomId) } returns emptyFlow()
        every { loader.readReceiptChangesFlow(roomId) } returns emptyFlow()
        every { loader.sendingEvents(roomId) } returns emptyList()
        every { loader.chunkEventCount(any()) } returns 1L
        every { loader.chunkEventCountFrom(any(), any(), any()) } returns 1L
        every { loader.chunkSnapshot(any()) } answers { events[firstArg()].orEmpty() }
        every { loader.chunkSnapshotNewest(any(), any()) } answers { events[firstArg()].orEmpty() }
        every { loader.chunkSnapshotAfter(any(), any(), any()) } returns emptyList()
        every { loader.chunkSnapshotOlderThan(any(), any(), any(), any()) } returns emptyList()
        return loader
    }

    private fun TestScope.timeline(
            db: FakeSessionDatabase,
            loader: SqlChunkSnapshotLoader,
            pagination: PaginationTask,
            tokens: FetchEventTokenTask,
            stores: SessionStores = db.stores,
    ): SqlTimeline {
        val read = StandardTestDispatcher(testScheduler, "read")
        val main = StandardTestDispatcher(testScheduler, "main")
        return SqlTimeline(
                roomId = roomId,
                initialEventId = null,
                settings = TimelineSettings(initialSize = 1),
                coroutineDispatchers = MatrixCoroutineDispatchers(read, read, main, read, read),
                stores = stores,
                snapshotLoader = loader,
                paginationTask = pagination,
                fetchThreadTimelineTask = mockk(relaxed = true),
                contextOfEventTask = mockk(relaxed = true),
                fetchEventTokenTask = tokens,
                fetchRoomStartTask = mockk(relaxed = true),
                database = db.database,
                sessionDispatcher = read,
                readDispatcher = read,
                eventDecryptor = mockk(relaxed = true),
                timelineInput = TimelineInput(),
                clock = mockk(relaxed = true),
                redactionSignal = TimelineRedactionSignal(),
                decryptionSignal = TimelineDecryptionSignal(),
                loadRoomMembersTask = mockk(relaxed = true),
                slidingSyncRoomSubscriptions = mockk(relaxed = true),
        )
    }

    private fun insertRow(db: FakeSessionDatabase, event: TimelineEvent, chunkId: Long) {
        val dbId = db.stores.event.insert(EventEntity(
                eventId = event.eventId, roomId = roomId, type = EventType.MESSAGE, sender = "@sender:example.org",
                originServerTs = event.root.originServerTs
        ))
        db.stores.timelineEvent.insert(TimelineEventEntity(eventId = event.eventId, roomId = roomId), chunkId = chunkId, rootEventDbId = dbId)
    }

    private fun message(eventId: String, timestamp: Long) = TimelineEvent(
            root = Event(eventId = eventId, roomId = roomId, type = EventType.MESSAGE, originServerTs = timestamp),
            localId = timestamp,
            eventId = eventId,
            senderInfo = SenderInfo("@sender:example.org", null, true, null),
    )
}
