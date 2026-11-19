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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.TimelineSettings
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
internal class SqlTimelineBackwardRevealTest {

    private val roomId = "!room:example.org"
    private val cached = listOf(message("\$e5", 5L), message("\$e4", 4L))
    private val fetched = listOf(message("\$e3", 3L), message("\$e2", 2L), message("\$e1", 1L))

    @Test
    fun `the first backward page of a short live range shows as soon as it is stored`() = runTest {
        val db = FakeSessionDatabase()
        val live = db.stores.chunk.insert(roomId, "live-prev", null, true, false, null, false)
        cached.forEach { insertRow(db, it, live) }
        var stored = false
        val loader = mockk<SqlChunkSnapshotLoader>()
        every { loader.chunkChangesFlow(any()) } returns emptyFlow()
        every { loader.sendingChangesFlow(roomId) } returns emptyFlow()
        every { loader.ignoredUserIdsFlow() } returns emptyFlow()
        every { loader.annotationSummaryChangesFlow(roomId) } returns emptyFlow()
        every { loader.readReceiptChangesFlow(roomId) } returns emptyFlow()
        every { loader.sendingEvents(roomId) } returns emptyList()
        every { loader.chunkEventCount(live) } answers { if (stored) 5L else 2L }
        every { loader.chunkEventCountFrom(live, any(), any()) } returns cached.size.toLong()
        every { loader.chunkSnapshotNewest(live, any()) } returns cached
        every { loader.chunkSnapshotAfter(live, any(), any()) } returns emptyList()
        every { loader.chunkSnapshotOlderThan(live, any(), any(), any()) } answers { if (stored) fetched else emptyList() }
        val pagination = mockk<PaginationTask>()
        coEvery { pagination.execute(any()) } answers {
            stored = true
            firstArg<PaginationTask.Params>().stats?.written = fetched.size
            TokenChunkEventPersistor.Result.SUCCESS
        }
        val read = StandardTestDispatcher(testScheduler, "read")
        val main = StandardTestDispatcher(testScheduler, "main")
        val timeline = SqlTimeline(
                roomId = roomId,
                initialEventId = null,
                settings = TimelineSettings(initialSize = 5),
                coroutineDispatchers = MatrixCoroutineDispatchers(read, read, main, read, read),
                stores = db.stores,
                snapshotLoader = loader,
                paginationTask = pagination,
                fetchThreadTimelineTask = mockk(relaxed = true),
                contextOfEventTask = mockk(relaxed = true),
                fetchEventTokenTask = mockk(relaxed = true),
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
            // Before the delayed member load, whose cache reset would reveal the page on its own.
            runCurrent()

            assertEquals((cached + fetched).map { it.eventId }, timeline.getSnapshot().map { it.eventId })
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
