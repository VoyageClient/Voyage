/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.TimelineSettings
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
internal class SqlTimelineSeedRecoveryTest {

    private val roomId = "!room:example.org"

    @Test
    fun `a jump range folded into another keeps the shown window without blanking`() = assertSeedRecovery(fromSync = false)

    @Test
    fun `sync follows a merged history range instead of returning to live`() = assertSeedRecovery(fromSync = true)

    @Test
    fun `sync replacing the live range preserves a jump into its history`() = assertSeedRecovery(fromSync = true, demotedLive = true)

    @Test
    fun `a backward page merging away its range preserves both shown window edges`() = assertSeedRecovery(fromSync = false, paginatedMerge = true)

    private fun assertSeedRecovery(fromSync: Boolean, demotedLive: Boolean = false, paginatedMerge: Boolean = false) = runTest {
        val db = FakeSessionDatabase()
        val island = db.stores.chunk.insert(roomId, "prev", "next", demotedLive, false, null, false)
        val survivor = db.stores.chunk.insert(roomId, "prev2", null, false, false, null, false)
        val live = db.stores.chunk.insert(roomId, "live-prev", null, !demotedLive, false, null, false)
        val latest = message("\$latest", 10L)
        insertRow(db, latest, live)
        val older = message("\$older", 1L)
        val target = message("\$target", 2L)
        val newer = message("\$newer", 3L)
        insertRow(db, older, island)
        insertRow(db, target, island)
        insertRow(db, newer, survivor)
        val main = StandardTestDispatcher(testScheduler, "main")
        val read = StandardTestDispatcher(testScheduler, "read")
        val decryptionSignal = TimelineDecryptionSignal()
        val loader = mockk<SqlChunkSnapshotLoader>()
        every { loader.chunkChangesFlow(live) } returns emptyFlow()
        every { loader.chunkEventCount(live) } returns 1L
        every { loader.chunkSnapshotNewest(live, any()) } returns listOf(latest)
        every { loader.chunkSnapshotAfter(live, any(), any()) } returns emptyList()
        every { loader.chunkEventCountFrom(live, any(), any()) } returns 1L
        every { loader.chunkChangesFlow(island) } returns emptyFlow()
        every { loader.chunkChangesFlow(survivor) } returns emptyFlow()
        every { loader.sendingChangesFlow(roomId) } returns emptyFlow()
        every { loader.ignoredUserIdsFlow() } returns emptyFlow()
        every { loader.annotationSummaryChangesFlow(roomId) } returns emptyFlow()
        every { loader.readReceiptChangesFlow(roomId) } returns emptyFlow()
        every { loader.sendingEvents(roomId) } returns emptyList()
        every { loader.chunkEventCount(island) } returns 2L
        every { loader.chunkSnapshot(island) } returns listOf(target, older)
        every { loader.chunkEventCountFrom(island, any(), any()) } returns 2L
        every { loader.chunkSnapshotAfter(island, any(), any()) } returns emptyList()
        val survivorEvents = (if (paginatedMerge) (20L downTo 4L).map { message("\$newer$it", it) } else emptyList()) + listOf(newer, target, older)
        every { loader.chunkEventCount(survivor) } returns survivorEvents.size.toLong()
        every { loader.chunkEventCountFrom(survivor, any(), any()) } returns survivorEvents.size.toLong()
        every { loader.chunkSnapshotNewest(survivor, any()) } returns survivorEvents
        every { loader.chunkSnapshotAfter(survivor, any(), any()) } returns emptyList()
        val pagination = mockk<PaginationTask>(relaxed = true)
        coEvery { pagination.execute(any()) } coAnswers {
            db.stores.chunk.mergeInto(survivor, island)
            firstArg<PaginationTask.Params>().stats?.apply {
                landedChunkId = survivor
                rowsMoved = true
            }
            TokenChunkEventPersistor.Result.REACHED_END
        }
        val timeline = SqlTimeline(
                roomId = roomId,
                initialEventId = target.eventId,
                settings = TimelineSettings(initialSize = 1),
                coroutineDispatchers = MatrixCoroutineDispatchers(read, read, main, read, read),
                stores = db.stores,
                snapshotLoader = loader,
                paginationTask = pagination,
                fetchThreadTimelineTask = mockk(relaxed = true),
                contextOfEventTask = mockk(relaxed = true),
                fetchRoomStartTask = mockk(relaxed = true),
                database = db.database,
                sessionDispatcher = read,
                readDispatcher = read,
                eventDecryptor = mockk(relaxed = true),
                timelineInput = TimelineInput(),
                clock = mockk(relaxed = true),
                redactionSignal = TimelineRedactionSignal(),
                decryptionSignal = decryptionSignal,
                loadRoomMembersTask = mockk(relaxed = true),
                slidingSyncRoomSubscriptions = mockk(relaxed = true),
        )
        val snapshots = mutableListOf<List<String>>()
        timeline.addListener(object : Timeline.Listener {
            override fun onTimelineUpdated(snapshot: List<TimelineEvent>) {
                snapshots.add(snapshot.map { it.eventId })
            }
        })
        try {
            timeline.start(null)
            runCurrent()
            val shown = listOf(target.eventId, older.eventId)
            assertEquals(shown, timeline.getSnapshot().map { it.eventId })
            snapshots.clear()

            if (paginatedMerge) {
                timeline.paginate(Timeline.Direction.BACKWARDS, 50)
            } else if (demotedLive) {
                db.stores.chunk.setLastForward(island, false)
                db.stores.chunk.setLastForward(live, true)
            } else {
                db.stores.chunk.mergeInto(survivor, island)
                assertNull(db.stores.chunk.getById(island))
            }
            // Not via the range's own flow: recovery cancels that collector, which would hide an empty publish.
            if (fromSync) timeline.onNewTimelineEvents(roomId, listOf(latest.eventId))
            else if (!paginatedMerge) decryptionSignal.onDecrypted(roomId)
            advanceTimeBy(1_000)
            runCurrent()

            snapshots.forEach { assertTrue("Timeline lost its jump target while following the fold: $it", target.eventId in it) }
            assertEquals(shown, timeline.getSnapshot().map { it.eventId })
            if (demotedLive) assertTrue(timeline.hasMoreToLoad(Timeline.Direction.FORWARDS))
        } finally {
            timeline.dispose()
            runCurrent()
            db.close()
        }
    }

    @Test
    fun `an older context fetch cannot replace a newer jump`() = assertNavigationRace(cancelToCurrent = false)

    @Test
    fun `cancelling a pending jump keeps the current window when its fetch finishes`() = assertNavigationRace(cancelToCurrent = true)

    @Test
    fun `an older pagination request cannot expand the window of a newer jump`() = assertNavigationRace(cancelToCurrent = false, delayedPage = true)

    private fun assertNavigationRace(cancelToCurrent: Boolean, delayedPage: Boolean = false) = runTest {
        val db = FakeSessionDatabase()
        val current = message("\$current", 3L)
        val first = message("\$first", 1L)
        val second = message("\$second", 2L)
        val currentChunk = db.stores.chunk.insert(roomId, if (delayedPage) "older" else null, null, true, !delayedPage, null, false)
        val firstChunk = db.stores.chunk.insert(roomId, null, null, false, true, null, false)
        val secondChunk = db.stores.chunk.insert(roomId, null, null, false, true, null, false)
        insertRow(db, current, currentChunk)
        insertRow(db, second, secondChunk)
        val secondEvents = if (delayedPage) listOf(second) + (1L..4L).map { message("\$older$it", -it) } else listOf(second)
        val events = mapOf(currentChunk to listOf(current), firstChunk to listOf(first), secondChunk to secondEvents)
        val loader = mockk<SqlChunkSnapshotLoader>()
        every { loader.chunkChangesFlow(any()) } returns emptyFlow()
        every { loader.sendingChangesFlow(roomId) } returns emptyFlow()
        every { loader.ignoredUserIdsFlow() } returns emptyFlow()
        every { loader.annotationSummaryChangesFlow(roomId) } returns emptyFlow()
        every { loader.readReceiptChangesFlow(roomId) } returns emptyFlow()
        every { loader.sendingEvents(roomId) } returns emptyList()
        every { loader.chunkEventCount(any()) } answers { events.getValue(firstArg()).size.toLong() }
        every { loader.chunkEventCountFrom(any(), any(), any()) } answers { events.getValue(firstArg()).size.toLong() }
        every { loader.chunkSnapshot(any()) } answers { events.getValue(firstArg()) }
        every { loader.chunkSnapshotNewest(any(), any()) } answers { events.getValue(firstArg()) }
        every { loader.chunkSnapshotAfter(any(), any(), any()) } returns emptyList()
        val context = mockk<GetContextOfEventTask>()
        val finishFetch = CompletableDeferred<Unit>()
        var fetching = false
        val pagination = mockk<PaginationTask>(relaxed = true)
        coEvery { pagination.execute(any()) } coAnswers {
            fetching = true
            finishFetch.await()
            TokenChunkEventPersistor.Result.REACHED_END
        }
        coEvery { context.execute(GetContextOfEventTask.Params(roomId, first.eventId)) } coAnswers {
            withContext(NonCancellable) {
                fetching = true
                finishFetch.await()
                insertRow(db, first, firstChunk)
                TokenChunkEventPersistor.Result.SUCCESS
            }
        }
        val read = StandardTestDispatcher(testScheduler, "read")
        val main = StandardTestDispatcher(testScheduler, "main")
        val timeline = SqlTimeline(
                roomId = roomId,
                initialEventId = current.eventId,
                settings = TimelineSettings(initialSize = 1),
                coroutineDispatchers = MatrixCoroutineDispatchers(read, read, main, read, read),
                stores = db.stores,
                snapshotLoader = loader,
                paginationTask = pagination,
                fetchThreadTimelineTask = mockk(relaxed = true),
                contextOfEventTask = context,
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
        try {
            timeline.start(null)
            runCurrent()
            if (delayedPage) timeline.paginate(Timeline.Direction.BACKWARDS, 50) else timeline.restartWithEventId(first.eventId)
            runCurrent()
            assertTrue(fetching)
            if (cancelToCurrent) timeline.cancelPendingNavigation() else timeline.restartWithEventId(second.eventId)
            runCurrent()
            val expected = (if (cancelToCurrent) listOf(current) else secondEvents.take(2)).map { it.eventId }
            assertEquals(expected, timeline.getSnapshot().map { it.eventId })
            finishFetch.complete(Unit)
            runCurrent()
            assertEquals(expected, timeline.getSnapshot().map { it.eventId })
        } finally {
            finishFetch.complete(Unit)
            timeline.dispose()
            runCurrent()
            db.close()
        }
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
