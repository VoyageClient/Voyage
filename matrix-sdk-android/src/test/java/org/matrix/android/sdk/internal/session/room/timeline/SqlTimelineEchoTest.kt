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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
internal class SqlTimelineEchoTest {

    @Test
    fun `a refresh built before an echo cannot remove it from the timeline`() = runTest {
        val db = FakeSessionDatabase()
        val roomId = "!room:example.org"
        val chunkId = db.stores.chunk.insert(roomId, null, null, true, true, null, false)
        val main = StandardTestDispatcher(testScheduler, "main")
        val read = StandardTestDispatcher(testScheduler, "read")
        val chunkChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val previous = message(roomId, "\$previous", 1L)
        val incoming = message(roomId, "\$incoming", 2L)
        val echo = message(roomId, "\$local.echo", 3L)
        val loader = mockk<SqlChunkSnapshotLoader>()
        every { loader.chunkChangesFlow(chunkId) } returns chunkChanges
        every { loader.sendingChangesFlow(roomId) } returns emptyFlow()
        every { loader.ignoredUserIdsFlow() } returns emptyFlow()
        every { loader.annotationSummaryChangesFlow(roomId) } returns emptyFlow()
        every { loader.readReceiptChangesFlow(roomId) } returns emptyFlow()
        every { loader.sendingEvents(roomId) } returns emptyList()
        every { loader.chunkEventCount(chunkId) } returns 1L
        every { loader.chunkSnapshotNewest(chunkId, any()) } returns listOf(previous)
        every { loader.chunkSnapshotAfter(chunkId, any(), any()) } returns emptyList()
        every { loader.chunkEventCountFrom(chunkId, any(), any()) } returns 1L
        val timeline = SqlTimeline(
                roomId = roomId,
                initialEventId = null,
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
                decryptionSignal = TimelineDecryptionSignal(),
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
            assertEquals(listOf(previous.eventId), timeline.getSnapshot().map { it.eventId })
            snapshots.clear()
            var announced = false
            every { loader.chunkEventCountFrom(chunkId, any(), any()) } returns 2L
            every { loader.chunkSnapshotAfter(chunkId, any(), any()) } answers {
                if (!announced) {
                    announced = true
                    // Queue the echo on main before this refresh publishes its older snapshot there.
                    timeline.onLocalEchoCreated(roomId, echo)
                    listOf(incoming)
                } else {
                    emptyList()
                }
            }

            chunkChanges.emit(Unit)
            runCurrent()

            assertTrue(announced)
            assertTrue(snapshots.isNotEmpty())
            snapshots.forEach { assertTrue("Echo disappeared from $it", echo.eventId in it) }
            assertEquals(listOf(echo.eventId, incoming.eventId, previous.eventId), timeline.getSnapshot().map { it.eventId })
        } finally {
            timeline.dispose()
            runCurrent()
            db.close()
        }
    }

    private fun message(roomId: String, eventId: String, timestamp: Long) = TimelineEvent(
            root = Event(eventId = eventId, roomId = roomId, type = EventType.MESSAGE, originServerTs = timestamp),
            localId = timestamp,
            eventId = eventId,
            senderInfo = SenderInfo("@sender:example.org", null, true, null),
    )
}
