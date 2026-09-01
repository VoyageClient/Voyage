/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.matrix.android.sdk.internal.session.room.timeline.PaginationDirection
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private const val ROOM_ID = "!room:hs"
private const val HOUR = 60 * 60 * 1000L
private const val DAY = 24 * HOUR

@RunWith(RobolectricTestRunner::class)
class TimelineOrderRepairerTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var database: SessionSqlDatabase
    private lateinit var stores: SessionStores

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = SessionSqlDatabase.Schema)
        database = SessionSqlDatabase(driver)
        stores = SessionStores(database)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a late event lands at its timestamp position in the live chunk`() {
        val chunkId = insertChunk(isLastForward = true)
        addEvent(chunkId, "\$a", ts = 3 * DAY)
        addEvent(chunkId, "\$b", ts = 4 * DAY)
        addEvent(chunkId, "\$c", ts = 5 * DAY)

        addEvent(chunkId, "\$late", ts = 3 * DAY + HOUR)

        orderOf(chunkId) shouldBeEqualTo listOf("\$a", "\$late", "\$b", "\$c")
    }

    @Test
    fun `an event delayed by less than the threshold is left where the server put it`() {
        val chunkId = insertChunk(isLastForward = true)
        addEvent(chunkId, "\$a", ts = 3 * DAY)
        addEvent(chunkId, "\$b", ts = 3 * DAY + 10 * 60 * 1000L)

        addEvent(chunkId, "\$late", ts = 3 * DAY + 5 * 60 * 1000L)

        orderOf(chunkId) shouldBeEqualTo listOf("\$a", "\$b", "\$late")
    }

    @Test
    fun `an event older than the live chunk moves into the chunk that spans it`() {
        val historyChunkId = insertChunk()
        // A backward page arrives newest-first.
        addEvent(historyChunkId, "\$old2", ts = 2 * DAY, direction = PaginationDirection.BACKWARDS)
        addEvent(historyChunkId, "\$old1", ts = 1 * DAY, direction = PaginationDirection.BACKWARDS)
        val liveChunkId = insertChunk(isLastForward = true, prevChunkId = historyChunkId)
        addEvent(liveChunkId, "\$new", ts = 5 * DAY)

        addEvent(liveChunkId, "\$late", ts = 1 * DAY + HOUR)

        orderOf(historyChunkId) shouldBeEqualTo listOf("\$old1", "\$late", "\$old2")
        orderOf(liveChunkId) shouldBeEqualTo listOf("\$new")
    }

    @Test
    fun `an event with nowhere to go waits for its region and is placed once it arrives`() {
        val liveChunkId = insertChunk(isLastForward = true)
        addEvent(liveChunkId, "\$new", ts = 5 * DAY)
        addEvent(liveChunkId, "\$late", ts = 1 * DAY + HOUR)

        orderOf(liveChunkId) shouldBeEqualTo listOf("\$new", "\$late")

        val historyChunkId = insertChunk(prevChunkId = null)
        // A backward page arrives newest-first.
        addEvent(historyChunkId, "\$old2", ts = 2 * DAY, direction = PaginationDirection.BACKWARDS)
        addEvent(historyChunkId, "\$old1", ts = 1 * DAY, direction = PaginationDirection.BACKWARDS)
        stores.timelineOrder.retryUnplaced(ROOM_ID)

        orderOf(historyChunkId) shouldBeEqualTo listOf("\$old1", "\$late", "\$old2")
        orderOf(liveChunkId) shouldBeEqualTo listOf("\$new")
    }

    @Test
    fun `the room sweep re-places events stored out of order before it ran`() {
        val chunkId = insertChunk(isLastForward = true)
        insertRow(chunkId, "\$a", ts = 3 * DAY, displayIndex = 1)
        insertRow(chunkId, "\$b", ts = 4 * DAY, displayIndex = 2)
        insertRow(chunkId, "\$late", ts = 3 * DAY + HOUR, displayIndex = 3)

        stores.timelineOrder.sweepRoom(ROOM_ID)

        orderOf(chunkId) shouldBeEqualTo listOf("\$a", "\$late", "\$b")
    }

    @Test
    fun `the room sweep leaves an in-order chunk untouched`() {
        val chunkId = insertChunk(isLastForward = true)
        insertRow(chunkId, "\$a", ts = 3 * DAY, displayIndex = 1)
        insertRow(chunkId, "\$b", ts = 4 * DAY, displayIndex = 2)
        insertRow(chunkId, "\$c", ts = 5 * DAY, displayIndex = 3)

        stores.timelineOrder.sweepRoom(ROOM_ID)

        orderOf(chunkId) shouldBeEqualTo listOf("\$a", "\$b", "\$c")
        stores.timelineEvent.getChunkRowsWithTs(chunkId).map { it.displayIndex } shouldBeEqualTo listOf(1L, 2L, 3L)
    }

    @Test
    fun `a scrambled backward page is written in timestamp order`() {
        val chunkId = insertChunk()
        // The server hands back a page whose middle event is days out of place.
        addEvent(chunkId, "\$p1", ts = 5 * DAY, direction = PaginationDirection.BACKWARDS)
        addEvent(chunkId, "\$p2", ts = 2 * DAY, direction = PaginationDirection.BACKWARDS)
        addEvent(chunkId, "\$p3", ts = 4 * DAY, direction = PaginationDirection.BACKWARDS)

        orderOf(chunkId) shouldBeEqualTo listOf("\$p2", "\$p3", "\$p1")
    }

    private fun insertChunk(isLastForward: Boolean = false, prevChunkId: Long? = null): Long =
            stores.chunk.insert(
                    roomId = ROOM_ID, prevToken = null, nextToken = null, prevChunkId = prevChunkId, nextChunkId = null,
                    isLastForward = isLastForward, isLastBackward = false, rootThreadEventId = null, isLastForwardThread = false,
            )

    /** Write an event the way the sync/pagination path does, order repair included. */
    private fun addEvent(chunkId: Long, eventId: String, ts: Long, direction: PaginationDirection = PaginationDirection.FORWARDS) {
        val entity = EventEntity(eventId = eventId, roomId = ROOM_ID, type = EventType.MESSAGE, sender = "@a:hs", originServerTs = ts)
        val dbId = stores.event.insert(entity)
        stores.timelineWriter.addTimelineEvent(
                chunkId = chunkId, roomId = ROOM_ID, eventDbId = dbId, event = entity,
                isLastForward = false, direction = direction, keepTimestampOrder = true,
        )
    }

    /** Write an event straight into a display slot, as a build without order repair would have. */
    private fun insertRow(chunkId: Long, eventId: String, ts: Long, displayIndex: Int) {
        val entity = EventEntity(eventId = eventId, roomId = ROOM_ID, type = EventType.MESSAGE, sender = "@a:hs", originServerTs = ts)
        val dbId = stores.event.insert(entity)
        stores.timelineWriter.addTimelineEvent(
                chunkId = chunkId, roomId = ROOM_ID, eventDbId = dbId, event = entity,
                isLastForward = false, direction = PaginationDirection.FORWARDS,
        )
        stores.timelineEvent.getPlacement(ROOM_ID, eventId)!!.let {
            stores.timelineEvent.moveToChunkAtIndex(it.id, chunkId, displayIndex.toLong())
        }
    }

    private fun orderOf(chunkId: Long): List<String> = stores.timelineEvent.getChunkRowsWithTs(chunkId).map { it.eventId }
}
