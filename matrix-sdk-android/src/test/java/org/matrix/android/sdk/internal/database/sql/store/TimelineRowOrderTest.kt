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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private const val ROOM_ID = "!room:hs"
private const val HOUR = 60 * 60 * 1000L

@RunWith(RobolectricTestRunner::class)
class TimelineRowOrderTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var database: SessionSqlDatabase
    private lateinit var stores: SessionStores
    private var chunkId: Long = 0

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = SessionSqlDatabase.Schema)
        database = SessionSqlDatabase(driver)
        stores = SessionStores(database)
        chunkId = stores.chunk.insert(ROOM_ID, null, null, true, false, null, false)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `rows read back newest first whatever order they were written in`() {
        add("\$b", ts = 20 * HOUR)
        add("\$a", ts = 10 * HOUR)
        add("\$c", ts = 30 * HOUR)

        ids() shouldBeEqualTo listOf("\$c", "\$b", "\$a")
    }

    @Test
    fun `an event that arrives after newer ones lands at its own timestamp`() {
        add("\$newest", ts = 30 * HOUR)
        add("\$newer", ts = 20 * HOUR)

        add("\$late", ts = 25 * HOUR)

        ids() shouldBeEqualTo listOf("\$newest", "\$late", "\$newer")
    }

    @Test
    fun `events sharing a timestamp still have one definite order`() {
        add("\$aaa", ts = 10 * HOUR)
        add("\$ccc", ts = 10 * HOUR)
        add("\$bbb", ts = 10 * HOUR)

        ids() shouldBeEqualTo listOf("\$ccc", "\$bbb", "\$aaa")
    }

    @Test
    fun `writing an event twice does not duplicate it`() {
        add("\$a", ts = 10 * HOUR)
        add("\$a", ts = 10 * HOUR)

        ids() shouldBeEqualTo listOf("\$a")
    }

    @Test
    fun `the older-than cursor walks back without repeating or skipping`() {
        (1..10).forEach { add("\$e$it", ts = it * HOUR) }

        val firstPage = stores.timelineEvent.getByChunkOlderThan(chunkId, 8 * HOUR, "\$e8", limit = 3)
        val secondPage = firstPage.last().let { stores.timelineEvent.getByChunkOlderThan(chunkId, it.ts, it.eventId, limit = 3) }

        firstPage.map { it.eventId } shouldBeEqualTo listOf("\$e7", "\$e6", "\$e5")
        secondPage.map { it.eventId } shouldBeEqualTo listOf("\$e4", "\$e3", "\$e2")
    }

    @Test
    fun `the newer-than cursor reports only what arrived above it`() {
        (1..5).forEach { add("\$e$it", ts = it * HOUR) }

        val since = stores.timelineEvent.getByChunkNewerThan(chunkId, 3 * HOUR, "\$e3")

        since.map { it.eventId } shouldBeEqualTo listOf("\$e5", "\$e4")
    }

    /** A cursor over equal timestamps has to break the tie the same way the ordering does, or it loops. */
    @Test
    fun `cursors are stable across a run of equal timestamps`() {
        listOf("\$a", "\$b", "\$c", "\$d").forEach { add(it, ts = 10 * HOUR) }

        val older = stores.timelineEvent.getByChunkOlderThan(chunkId, 10 * HOUR, "\$c", limit = 10)

        older.map { it.eventId } shouldBeEqualTo listOf("\$b", "\$a")
    }

    @Test
    fun `rows moved into a neighbour keep their place in the order`() {
        val other = stores.chunk.insert(ROOM_ID, null, null, false, false, null, false)
        add("\$old", ts = 10 * HOUR)
        add("\$mid", ts = 20 * HOUR, chunk = other)
        add("\$new", ts = 30 * HOUR, chunk = other)

        stores.timelineEvent.moveRowsFromTs(other, chunkId, 20 * HOUR) shouldBeEqualTo 2

        ids() shouldBeEqualTo listOf("\$new", "\$mid", "\$old")
    }

    /** What tells an open timeline its mapped window is still whole, without re-reading the window. */
    @Test
    fun `counting from a cursor ignores rows appended below it`() {
        add("\$mid", ts = 20 * HOUR)
        add("\$new", ts = 30 * HOUR)

        stores.timelineEvent.countByChunkFrom(chunkId, 20 * HOUR, "\$mid") shouldBeEqualTo 2L

        // A backward page lands below the cursor: the window is untouched.
        add("\$old", ts = 10 * HOUR)
        stores.timelineEvent.countByChunkFrom(chunkId, 20 * HOUR, "\$mid") shouldBeEqualTo 2L

        // A late event inside the window moves it.
        add("\$late", ts = 25 * HOUR)
        stores.timelineEvent.countByChunkFrom(chunkId, 20 * HOUR, "\$mid") shouldBeEqualTo 3L
    }

    private fun ids() = stores.timelineEvent.getByChunk(chunkId).map { it.eventId }

    private fun add(eventId: String, ts: Long, chunk: Long = chunkId) {
        val entity = EventEntity(eventId = eventId, roomId = ROOM_ID, type = EventType.MESSAGE, sender = "@a:hs", originServerTs = ts)
        val dbId = stores.event.insert(entity)
        stores.timelineWriter.addTimelineEvent(
                chunkId = chunk, roomId = ROOM_ID, eventDbId = dbId, event = entity, isLastForward = false,
        )
    }
}
