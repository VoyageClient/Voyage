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

    private fun anEvent(id: String, ts: Long = 1_000L) = Event(
            eventId = id,
            type = "m.room.message",
            senderId = "@other:example.org",
            roomId = A_ROOM_ID,
            content = mapOf("body" to "hello $id", "msgtype" to "m.text"),
            originServerTs = ts,
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
    fun `re-paginating an already-stored page folds the island into the origin range`() = runTest {
        // A live range (from sync) whose prev boundary token is a suffixed variant of the island's next
        // boundary (Synapse appends a stream suffix, so the strings differ even at the same boundary).
        val liveId = db.stores.chunk.insert(A_ROOM_ID, "B_0", null, true, false, null, false)
        // A separate island (e.g. from jump-to-event) holding the same event the page will return.
        val islandId = db.stores.chunk.insert(A_ROOM_ID, "A", "B", false, false, null, false)
        val islandEvent = anEvent("\$old")
        persistor.insertInDb(aChunk("B", "A", listOf(islandEvent)), A_ROOM_ID, PaginationDirection.BACKWARDS, originChunkId = islandId)

        // Paginating the live range's prev_token returns the same page. Nothing is linked: the two ranges
        // describe the same history, so they become one — which is what makes it reachable.
        persistor.insertInDb(aChunk("B", "A", listOf(islandEvent)), A_ROOM_ID, PaginationDirection.BACKWARDS, originChunkId = liveId)

        assertNull("the island must have been folded away", db.stores.chunk.getById(islandId))
        assertEquals("one range left", 1, db.stores.chunk.getByRoom(A_ROOM_ID).size)
        assertNotNull(
                "the event must live in the surviving range",
                db.stores.timelineEvent.getInChunkByEventId(liveId, "\$old")
        )
    }

    @Test
    fun `chunk token lookups never match on a null token`() {
        // A live chunk has null tokens; a reached-room-start chunk has a null prev_token. Matching a
        // null query token against these would cross-link them into a pagination-trapping cycle.
        db.stores.chunk.insert(A_ROOM_ID, null, null, true, false, null, false)
        assertNull("null nextToken must not match the live chunk", db.stores.chunk.findByNextToken(A_ROOM_ID, null))
        assertNull("null prevToken must not match the live chunk", db.stores.chunk.findByPrevToken(A_ROOM_ID, null))

        db.stores.chunk.insert(A_ROOM_ID, "realPrev", "realNext", false, false, null, false)
        assertNotNull("a real token must still match", db.stores.chunk.findByNextToken(A_ROOM_ID, "realNext"))
        assertNotNull("a real token must still match", db.stores.chunk.findByPrevToken(A_ROOM_ID, "realPrev"))
    }

    @Test
    fun `a backward page overlapping the origin range adds no second range`() = runTest {
        // The live/join range: newest events J2, J1.
        persistor.insertInDb(aChunk("live", "p0", listOf(anEvent("\$J2"), anEvent("\$J1"))), A_ROOM_ID, PaginationDirection.BACKWARDS)
        val liveChunkId = db.stores.chunk.getByRoom(A_ROOM_ID).single().id
        // Paginating back returns only events the range already holds (pure boundary overlap): the server
        // token boundary didn't align, so the page re-delivers J1 with no older event.
        persistor.insertInDb(aChunk("p0", "p1", listOf(anEvent("\$J1"))), A_ROOM_ID, PaginationDirection.BACKWARDS, originChunkId = liveChunkId)

        assertEquals("the page belongs to the range it came from", 1, db.stores.chunk.getByRoom(A_ROOM_ID).size)
        val rows = db.database.timelineEventQueries.selectByRoom(A_ROOM_ID).executeAsList()
        assertEquals("no event may be stored twice", rows.size, rows.map { it.event_id }.distinct().size)
    }

    @Test
    fun `a page split at a detected gap keeps its newer side in the origin range`() = runTest {
        val originId = db.stores.chunk.insert(A_ROOM_ID, "page-start", null, true, false, null, false)
        persistor.insertInDb(
                receivedChunk = aChunk(
                        "page-start",
                        "page-end",
                        listOf(anEvent("\$near", 10_000L), anEvent("\$far", 1_000L)),
                ),
                roomId = A_ROOM_ID,
                direction = PaginationDirection.BACKWARDS,
                originChunkId = originId,
                split = TokenChunkEventPersistor.GapSplit(beforeEventId = "\$far"),
        )

        assertNotNull(db.stores.timelineEvent.getInChunkByEventId(originId, "\$near"))
        assertNull(db.stores.timelineEvent.getInChunkByEventId(originId, "\$far"))
        assertNull(db.stores.chunk.getById(originId)?.prev_token)
    }

    @Test
    fun `deleteDuplicatesInChunks keeps one copy per event and spares other chunks`() = runTest {
        // Two overlapping pages persisted the old way would duplicate; simulate directly
        persistor.insertInDb(aChunk("t1", "t2", listOf(anEvent("\$C"), anEvent("\$B"))), A_ROOM_ID, PaginationDirection.BACKWARDS)
        val chunk = db.stores.chunk.getByRoom(A_ROOM_ID).single()
        val otherChunkId = db.stores.chunk.insert(A_ROOM_ID, "x1", "x2", false, false, null, false)
        // Manually duplicate B into the other chunk (as legacy data would have it)
        val eventDbId = db.stores.event.getDbId(A_ROOM_ID, "\$B")!!
        db.database.timelineEventQueries.insert(99L, "\$B", A_ROOM_ID, otherChunkId, 0L, eventDbId, null, 0L, null, null, 0L)

        db.stores.timelineEvent.deleteDuplicatesInChunks(A_ROOM_ID, listOf(chunk.id, otherChunkId))

        val rows = db.database.timelineEventQueries.selectByRoom(A_ROOM_ID).executeAsList()
        assertEquals(listOf(1, 1), rows.groupingBy { it.event_id }.eachCount().values.toList().sorted())
    }

    @Test
    fun `absorbing a jump-to-event island moves its event into the absorbing chunk`() = runTest {
        // A jump-to-event island: one event, its own chunk. A timeline seeded on a jump watches exactly this.
        persistor.insertInDb(aChunk("i1", "i2", listOf(anEvent("\$T"))), A_ROOM_ID, PaginationDirection.FORWARDS)
        val islandId = db.stores.chunk.getByRoom(A_ROOM_ID).single().id

        // A page re-covering the island's region absorbs it — SqlTimeline's recovery then has to find the
        // event again by chunk, so the absorber must own it and the island must be gone.
        persistor.insertInDb(aChunk("p1", "p2", listOf(anEvent("\$U"), anEvent("\$T"), anEvent("\$S"))), A_ROOM_ID, PaginationDirection.BACKWARDS)

        assertNull("the absorbed island must be deleted", db.stores.chunk.getById(islandId))
        val owner = db.stores.chunk.findChunkIdIncludingEvent(A_ROOM_ID, "\$T")
        assertNotNull("the jumped-to event must still resolve to a chunk", owner)
        assertNotEquals("it must resolve to the absorbing chunk, not the retired island", islandId, owner)
    }

    @Test
    fun `splicing joins ranges that share an event`() = runTest {
        val shared = anEvent("\$shared")
        persistor.insertInDb(aChunk("o1", "o2", listOf(shared)), A_ROOM_ID, PaginationDirection.BACKWARDS)
        val firstId = db.stores.chunk.getByRoom(A_ROOM_ID).single().id
        val secondId = db.stores.chunk.insert(A_ROOM_ID, "r1", "r2", false, false, null, false)
        persistor.insertInDb(aChunk("r2", "r1", listOf(shared)), A_ROOM_ID, PaginationDirection.BACKWARDS, originChunkId = secondId)

        persistor.spliceBackward(firstId, secondId)

        assertEquals("the two ranges hold the same history, so they are one", 1, db.stores.chunk.getByRoom(A_ROOM_ID).size)
    }

    /**
     * Only the gap healer splices, and only once the server has said the older range is what lies
     * immediately below the boundary. Leaving them apart there strands that history: nothing shares an
     * event across a timestamp jump, so no later merge would ever join them.
     */
    @Test
    fun `splicing joins a proven-adjacent range and keeps its frontier`() = runTest {
        persistor.insertInDb(aChunk("o1", "o2", listOf(anEvent("\$old"))), A_ROOM_ID, PaginationDirection.BACKWARDS)
        val olderId = db.stores.chunk.getByRoom(A_ROOM_ID).single().id
        val newerId = db.stores.chunk.insert(A_ROOM_ID, "n1", null, true, false, null, false)
        persistor.insertInDb(aChunk("n0", "n1", listOf(anEvent("\$new"))), A_ROOM_ID, PaginationDirection.BACKWARDS, originChunkId = newerId)

        val joined = persistor.spliceBackward(newerId, olderId)

        assertEquals("the ranges are now one", 1, db.stores.chunk.getByRoom(A_ROOM_ID).size)
        assertEquals("the caller is told it worked", true, joined)
        assertNotNull("the backward frontier of the older side survives", db.stores.chunk.getById(newerId)!!.prev_token)
        assertNotNull("both pieces of history are reachable", db.stores.timelineEvent.getInChunkByEventId(newerId, "\$old"))
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
