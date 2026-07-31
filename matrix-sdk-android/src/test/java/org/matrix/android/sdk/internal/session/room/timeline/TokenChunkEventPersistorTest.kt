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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `overlapping backward pagination skips duplicates but keeps the new older events`() = runTest {
        // Old stored chunk: events C, B (newest to oldest)
        persistor.insertInDb(aChunk("t1", "t2", listOf(anEvent("\$C"), anEvent("\$B"))), A_ROOM_ID, PaginationDirection.BACKWARDS)
        // A backward page whose newest event (C) overlaps the stored chunk, then genuinely older A.
        // The overlap is at the START of the page — the real-world case that link-and-stop dropped.
        persistor.insertInDb(aChunk("t2", "t8", listOf(anEvent("\$C"), anEvent("\$A"))), A_ROOM_ID, PaginationDirection.BACKWARDS)

        val rows = db.database.timelineEventQueries.selectByRoom(A_ROOM_ID).executeAsList()
        val countByEvent = rows.groupingBy { it.event_id }.eachCount()
        assertEquals("event C must not be duplicated", 1, countByEvent["\$C"])
        assertEquals("the genuinely older event A must be stored, not dropped", 1, countByEvent["\$A"])
    }

    @Test
    fun `re-paginating an already-stored page links the origin chunk to the island`() = runTest {
        // A live chunk (from sync) whose prev boundary token is a suffixed variant of the island's next
        // boundary (Synapse appends a stream suffix, so the strings differ even at the same boundary).
        val liveId = db.stores.chunk.insert(A_ROOM_ID, "B_0", null, null, null, true, false, null, false)
        // An unreachable island (e.g. from jump-to-event): its next boundary is B, not linked to live.
        val islandId = db.stores.chunk.insert(A_ROOM_ID, "A", "B", null, null, false, false, null, false)

        // Paginating the live chunk's prev_token returns the island's page. The origin (the chunk we
        // paginated from) is passed so linking doesn't depend on token strings matching — the
        // homeserver's boundary tokens don't reliably equal ours.
        persistor.insertInDb(aChunk("B", "A", listOf(anEvent("\$old"))), A_ROOM_ID, PaginationDirection.BACKWARDS, originChunkId = liveId)

        // The island must now be linked as the live chunk's prev, so its history is reachable.
        val live = db.stores.chunk.getById(liveId)!!
        val island = db.stores.chunk.getById(islandId)!!
        assertEquals(islandId, live.prev_chunk_id)
        assertEquals(liveId, island.next_chunk_id)
    }

    @Test
    fun `chunk token lookups never match on a null token`() {
        // A live chunk has null tokens; a reached-room-start chunk has a null prev_token. Matching a
        // null query token against these would cross-link them into a pagination-trapping cycle.
        db.stores.chunk.insert(A_ROOM_ID, null, null, null, null, true, false, null, false)
        assertNull("null nextToken must not match the live chunk", db.stores.chunk.findByNextToken(A_ROOM_ID, null))
        assertNull("null prevToken must not match the live chunk", db.stores.chunk.findByPrevToken(A_ROOM_ID, null))

        db.stores.chunk.insert(A_ROOM_ID, "realPrev", "realNext", null, null, false, false, null, false)
        assertNotNull("a real token must still match", db.stores.chunk.findByNextToken(A_ROOM_ID, "realNext"))
        assertNotNull("a real token must still match", db.stores.chunk.findByPrevToken(A_ROOM_ID, "realPrev"))
    }

    @Test
    fun `a backward page overlapping the origin chunk never creates a cycle`() = runTest {
        // The live/join chunk: newest events J2, J1.
        persistor.insertInDb(aChunk("live", "p0", listOf(anEvent("\$J2"), anEvent("\$J1"))), A_ROOM_ID, PaginationDirection.BACKWARDS)
        val liveChunkId = db.stores.chunk.getByRoom(A_ROOM_ID).single().id
        // Paginating back returns only events already in the live chunk (pure boundary overlap): the
        // server token boundary didn't align, so the page re-delivers J1 with no older event.
        persistor.insertInDb(aChunk("p0", "p1", listOf(anEvent("\$J1"))), A_ROOM_ID, PaginationDirection.BACKWARDS)

        // No chunk may point at the same neighbour both ways (the hang signature).
        db.stores.chunk.getByRoom(A_ROOM_ID).forEach { chunk ->
            if (chunk.prev_chunk_id != null) {
                assertNotEquals("chunk ${chunk.id} forms a 2-cycle", chunk.prev_chunk_id, chunk.next_chunk_id)
            }
        }
        // Following prev from the live edge must terminate at null, not loop back on itself.
        val visited = mutableSetOf<Long>()
        var cursor: Long? = liveChunkId
        var cycled = false
        while (cursor != null) {
            if (!visited.add(cursor)) {
                cycled = true
                break
            }
            cursor = db.stores.chunk.getById(cursor)?.prev_chunk_id
        }
        assertFalse("prev walk from the live edge cycled", cycled)
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
