/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import dagger.Lazy
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.settings.LightweightSettingsStorage
import org.matrix.android.sdk.internal.session.StreamEventsManager
import org.matrix.android.sdk.test.fakes.FakeClock
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.robolectric.RobolectricTestRunner

private const val A_ROOM_ID = "!room:example.org"

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
internal class TokenChunkEventPersistorTest {

    private val db = FakeSessionDatabase()
    private val fakeClock = FakeClock().also { it.givenEpoch(1_000_000L) }
    private val streamEventsManager = mockk<StreamEventsManager>(relaxed = true)

    private val persistor = TokenChunkEventPersistor(
            database = db.database,
            dispatcher = db.dispatcher,
            stores = db.stores,
            userId = "@me:example.org",
            lightweightSettingsStorage = mockk<LightweightSettingsStorage>(relaxed = true),
            liveEventManager = Lazy { streamEventsManager },
            clock = fakeClock,
    )

    @After
    fun tearDown() {
        db.close()
    }

    private fun anEvent(id: String) = Event(
            eventId = id,
            type = "m.room.message",
            senderId = "@other:example.org",
            roomId = A_ROOM_ID,
            content = mapOf("body" to "hello $id", "msgtype" to "m.text"),
            originServerTs = 1_000L,
    )

    private fun aChunk(startToken: String, endToken: String, chunkEvents: List<Event>) = object : TokenChunkEvent {
        override val start = startToken
        override val end = endToken
        override val events = chunkEvents
        override val stateEvents: List<Event>? = emptyList()
    }

    @Test
    fun `overlapping backward pagination links to the known chunk instead of duplicating events`() = runTest {
        // Old stored chunk: events C, B (newest to oldest)
        persistor.insertInDb(aChunk("t1", "t2", listOf(anEvent("\$C"), anEvent("\$B"))), A_ROOM_ID, PaginationDirection.BACKWARDS)
        // Backfill with non-matching tokens whose tail overlaps the stored chunk: D (new), then C again
        persistor.insertInDb(aChunk("t9", "t8", listOf(anEvent("\$D"), anEvent("\$C"))), A_ROOM_ID, PaginationDirection.BACKWARDS)

        val rows = db.database.timelineEventQueries.selectByRoom(A_ROOM_ID).executeAsList()
        val countByEvent = rows.groupingBy { it.event_id }.eachCount()
        assertEquals("event C must not be duplicated", 1, countByEvent["\$C"])
        assertEquals("event D must be stored", 1, countByEvent["\$D"])

        // The backfill chunk must be linked in front of the known chunk
        val chunks = db.stores.chunk.getByRoom(A_ROOM_ID).sortedBy { it.id }
        assertEquals(2, chunks.size)
        val (known, backfill) = chunks
        assertEquals(known.id, backfill.prev_chunk_id)
        assertEquals(backfill.id, known.next_chunk_id)
    }

    @Test
    fun `deleteDuplicatesInChunks keeps one copy per event and spares other chunks`() = runTest {
        // Two overlapping pages persisted the old way would duplicate; simulate directly
        persistor.insertInDb(aChunk("t1", "t2", listOf(anEvent("\$C"), anEvent("\$B"))), A_ROOM_ID, PaginationDirection.BACKWARDS)
        val chunk = db.stores.chunk.getByRoom(A_ROOM_ID).single()
        val otherChunkId = db.stores.chunk.insert(A_ROOM_ID, "x1", "x2", null, null, false, false, null, false)
        // Manually duplicate B into the other chunk (as legacy data would have it)
        val eventDbId = db.stores.event.getDbId(A_ROOM_ID, "\$B")!!
        db.database.timelineEventQueries.insert(99L, "\$B", A_ROOM_ID, otherChunkId, 0L, eventDbId, null, 0L, null, null, 0L)

        db.stores.timelineEvent.deleteDuplicatesInChunks(A_ROOM_ID, listOf(chunk.id, otherChunkId))

        val rows = db.database.timelineEventQueries.selectByRoom(A_ROOM_ID).executeAsList()
        assertEquals(listOf(1, 1), rows.groupingBy { it.event_id }.eachCount().values.toList().sorted())
    }

    @Test
    fun `non-overlapping pagination keeps all events`() = runTest {
        persistor.insertInDb(aChunk("t1", "t2", listOf(anEvent("\$C"), anEvent("\$B"))), A_ROOM_ID, PaginationDirection.BACKWARDS)
        persistor.insertInDb(aChunk("t2", "t5", listOf(anEvent("\$A"))), A_ROOM_ID, PaginationDirection.BACKWARDS)

        val rows = db.database.timelineEventQueries.selectByRoom(A_ROOM_ID).executeAsList()
        assertEquals(3, rows.size)
        assertEquals(rows.size, rows.map { it.event_id }.toSet().size)
    }
}
