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
import org.matrix.android.sdk.api.session.room.read.ReadService
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.ReadReceiptEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private const val ROOM_ID = "!room:hs"
private const val ME = "@me:hs"
private const val THEM = "@them:hs"
private const val HOUR = 60 * 60 * 1000L

@RunWith(RobolectricTestRunner::class)
class EventReadStateTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var stores: SessionStores
    private var chunkId: Long = 0

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = SessionSqlDatabase.Schema)
        stores = SessionStores(SessionSqlDatabase(driver))
        chunkId = stores.chunk.insert(ROOM_ID, null, null, true, false, null, false)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `an event newer than the receipt is unread`() {
        add("\$read", ts = 10 * HOUR)
        add("\$fresh", ts = 20 * HOUR)
        receipt("\$read", ts = 10 * HOUR)

        stores.isEventRead(ME, ROOM_ID, "\$fresh") shouldBeEqualTo false
    }

    @Test
    fun `an event older than the receipt is read`() {
        add("\$old", ts = 10 * HOUR)
        add("\$read", ts = 20 * HOUR)
        receipt("\$read", ts = 20 * HOUR)

        stores.isEventRead(ME, ROOM_ID, "\$old") shouldBeEqualTo true
    }

    @Test
    fun `the receipt's own event is read`() {
        add("\$read", ts = 10 * HOUR)
        receipt("\$read", ts = 10 * HOUR)

        stores.isEventRead(ME, ROOM_ID, "\$read") shouldBeEqualTo true
    }

    @Test
    fun `our own message is read without a receipt`() {
        add("\$mine", ts = 10 * HOUR, sender = ME)

        stores.isEventRead(ME, ROOM_ID, "\$mine") shouldBeEqualTo true
    }

    @Test
    fun `with no receipt at all nothing is read`() {
        add("\$theirs", ts = 10 * HOUR)

        stores.isEventRead(ME, ROOM_ID, "\$theirs") shouldBeEqualTo false
    }

    /** The receipt can point at an event this client was never sent; time is all there is to go on. */
    @Test
    fun `a receipt on an unknown event falls back to timestamps`() {
        add("\$older", ts = 10 * HOUR)
        add("\$newer", ts = 30 * HOUR)
        receipt("\$never-synced", ts = 20 * HOUR)

        stores.isEventRead(ME, ROOM_ID, "\$older") shouldBeEqualTo true
        stores.isEventRead(ME, ROOM_ID, "\$newer") shouldBeEqualTo false
    }

    @Test
    fun `a newer live range containing only state does not mark an older-range message read`() {
        add("\$read", ts = 10 * HOUR)
        add("\$fresh", ts = 20 * HOUR)
        receipt("\$read", ts = 10 * HOUR)
        val stateChunk = stores.chunk.insert(ROOM_ID, null, null, true, false, null, false)
        add("\$state", ts = 30 * HOUR, type = EventType.STATE_ROOM_MEMBER, targetChunkId = stateChunk)

        stores.isEventRead(ME, ROOM_ID, "\$fresh") shouldBeEqualTo false
    }

    @Test
    fun `a thread receipt from another client marks an older thread notification read`() {
        val threadId = "\$thread"
        add("\$thread-old", ts = 10 * HOUR, threadId = threadId)
        add("\$thread-read", ts = 20 * HOUR, threadId = threadId)
        receipt("\$thread-read", ts = 20 * HOUR, threadId = threadId)

        stores.isEventRead(ME, ROOM_ID, "\$thread-old", threadId) shouldBeEqualTo true
    }

    @Test
    fun `latest unread anchor skips edits threads and state`() {
        add("\$message", ts = 10 * HOUR)
        add("\$edit", ts = 20 * HOUR, content = """{"m.relates_to":{"rel_type":"m.replace"}}""")
        add("\$thread", ts = 30 * HOUR, threadId = "\$root")
        add("\$state", ts = 40 * HOUR, type = EventType.STATE_ROOM_MEMBER)

        stores.timelineEvent.getLatestUnreadEvent(
                ROOM_ID,
                listOf(EventType.MESSAGE, EventType.ENCRYPTED, EventType.STICKER),
                listOf("@ignored:hs"),
        )?.eventId shouldBeEqualTo "\$message"
    }

    @Test
    fun `batch read checks use sequence to split events with the same timestamp`() {
        add("\$before", ts = HOUR)
        add("\$read", ts = HOUR)
        add("\$after", ts = HOUR)
        add("\$own", ts = 2 * HOUR, sender = ME)
        receipt("\$read", ts = HOUR)

        stores.readEventIds(ME, ROOM_ID, listOf("\$before", "\$read", "\$after", "\$own", "\$missing"), null) shouldBeEqualTo
                setOf("\$before", "\$read", "\$own")
    }

    @Test
    fun `batch read checks do not use another threads receipt`() {
        add("\$older", ts = HOUR)
        add("\$newer", ts = 2 * HOUR)
        receipt("\$newer", ts = 2 * HOUR, threadId = "\$thread")

        stores.readEventIds(ME, ROOM_ID, listOf("\$older", "\$newer"), null) shouldBeEqualTo emptySet()
        stores.readEventIds(ME, ROOM_ID, listOf("\$older", "\$newer"), "\$thread") shouldBeEqualTo setOf("\$older", "\$newer")
    }

    @Test
    fun `batch read checks split large queues below sqlite bind limits`() {
        val ids = (1..1100).map { "\$event-$it" }
        ids.forEachIndexed { index, id -> add(id, ts = index.toLong()) }
        receipt(ids[749], ts = 749)

        stores.readEventIds(ME, ROOM_ID, ids, null) shouldBeEqualTo ids.take(750).toSet()
    }

    @Test
    fun `a duplicate timeline row does not change an events read position`() {
        add("\$receipt", ts = HOUR)
        add("\$unread", ts = HOUR)
        receipt("\$receipt", ts = HOUR)
        val duplicateChunk = stores.chunk.insert(ROOM_ID, null, null, false, false, null, false)
        stores.timelineEvent.insert(
                TimelineEventEntity(
                        eventId = "\$unread", roomId = ROOM_ID,
                        localId = stores.timelineEvent.nextLocalId(), ts = HOUR,
                ),
                chunkId = duplicateChunk,
                rootEventDbId = stores.event.getDbId(ROOM_ID, "\$unread"),
                atOlderEnd = true,
        )

        stores.readEventIds(ME, ROOM_ID, listOf("\$unread"), null) shouldBeEqualTo emptySet()
    }

    private fun add(
            eventId: String,
            ts: Long,
            sender: String = THEM,
            threadId: String? = null,
            type: String = EventType.MESSAGE,
            content: String? = null,
            targetChunkId: Long = chunkId,
    ) {
        val entity = EventEntity(
                eventId = eventId,
                roomId = ROOM_ID,
                type = type,
                sender = sender,
                originServerTs = ts,
                rootThreadEventId = threadId,
                content = content,
        )
        val dbId = stores.event.insert(entity)
        stores.timelineWriter.addTimelineEvent(
                chunkId = targetChunkId, roomId = ROOM_ID, eventDbId = dbId, event = entity, isLastForward = true,
        )
    }

    private fun receipt(eventId: String, ts: Long, threadId: String = ReadService.THREAD_ID_MAIN) {
        stores.readReceipt.upsertReceipt(
                ReadReceiptEntity(
                        primaryKey = "${ROOM_ID}_${ME}_$threadId",
                        eventId = eventId,
                        roomId = ROOM_ID,
                        userId = ME,
                        threadId = threadId,
                        originServerTs = ts.toDouble(),
                )
        )
    }
}
