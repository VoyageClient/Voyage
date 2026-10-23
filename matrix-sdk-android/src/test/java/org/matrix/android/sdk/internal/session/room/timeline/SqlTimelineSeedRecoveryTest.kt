/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
    fun `a jump range folded into another keeps the shown window without blanking`() = runTest {
        val db = FakeSessionDatabase()
        val island = db.stores.chunk.insert(roomId, "prev", "next", false, false, null, false)
        val survivor = db.stores.chunk.insert(roomId, "prev2", null, false, false, null, false)
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
        every { loader.chunkChangesFlow(island) } returns emptyFlow()
        every { loader.chunkChangesFlow(survivor) } returns emptyFlow()
        every { loader.sendingChangesFlow(roomId) } returns emptyFlow()
        every { loader.ignoredUserIdsFlow() } returns emptyFlow()
        every { loader.annotationSummaryChangesFlow(roomId) } returns emptyFlow()
        every { loader.readReceiptChangesFlow(roomId) } returns emptyFlow()
        every { loader.sendingEvents(roomId) } returns emptyList()
        every { loader.chunkEventCount(island) } returns 2L
        every { loader.chunkSnapshot(island) } returns listOf(target, older)
        every { loader.chunkEventCount(survivor) } returns 3L
        every { loader.chunkEventCountFrom(survivor, any(), any()) } returns 3L
        every { loader.chunkSnapshotNewest(survivor, any()) } returns listOf(newer, target, older)
        every { loader.chunkSnapshotAfter(survivor, any(), any()) } returns emptyList()
        val timeline = SqlTimeline(
                roomId = roomId,
                initialEventId = target.eventId,
                settings = TimelineSettings(initialSize = 1),
                coroutineDispatchers = MatrixCoroutineDispatchers(read, read, main, read, read),
                stores = db.stores,
                snapshotLoader = loader,
                paginationTask = mockk(relaxed = true),
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

            db.stores.chunk.mergeInto(survivor, island)
            assertNull(db.stores.chunk.getById(island))
            // Not via the range's own flow: recovery cancels that collector, which would hide an empty publish.
            decryptionSignal.onDecrypted(roomId)
            advanceTimeBy(1_000)
            runCurrent()

            snapshots.forEach { assertTrue("Timeline blanked while following the fold", it.isNotEmpty()) }
            assertEquals(shown, timeline.getSnapshot().map { it.eventId })
        } finally {
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
