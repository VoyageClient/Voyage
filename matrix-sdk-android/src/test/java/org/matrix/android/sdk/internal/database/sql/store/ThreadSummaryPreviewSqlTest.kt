/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The thread-root tile's inline preview: which reply row it points at, and when that pointer survives. */
@RunWith(RobolectricTestRunner::class)
class ThreadSummaryPreviewSqlTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var database: SessionSqlDatabase
    private lateinit var stores: SessionStores

    private val roomId = "!room:hs"
    private val rootEventId = "\$root"

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

    private fun insertReply(eventId: String, ts: Long): Long = stores.event.insert(EventEntity(
            eventId = eventId,
            roomId = roomId,
            type = EventType.MESSAGE,
            content = """{"body":"reply"}""",
            sender = "@bob:hs",
            originServerTs = ts,
            rootThreadEventId = rootEventId,
    ))

    private fun insertTimelineRow(eventId: String, eventDbId: Long, chunkId: Long?, ownedByThreadChunk: Boolean = false): Long =
            stores.timelineEvent.insert(
                    TimelineEventEntity(
                            eventId = eventId,
                            roomId = roomId,
                            senderName = "Bob",
                            ownedByThreadChunk = ownedByThreadChunk,
                    ),
                    chunkId = chunkId,
                    rootEventDbId = eventDbId,
            )

    private fun mainChunk() = stores.chunk.insert(
            roomId, null, null, null, null, isLastForward = true, isLastBackward = true, rootThreadEventId = null, isLastForwardThread = false)

    private fun threadChunk() = stores.chunk.insert(
            roomId, null, null, null, null, isLastForward = false, isLastBackward = false,
            rootThreadEventId = rootEventId, isLastForwardThread = true)

    @Test
    fun `latest reply prefers the main-chunk row over the open thread chunk duplicate`() {
        val replyDbId = insertReply("\$reply", ts = 2_000L)
        // Thread-chunk copy inserted first, so it wins on id order if nothing else separates them.
        val threadRowId = insertTimelineRow("\$reply", replyDbId, threadChunk(), ownedByThreadChunk = true)
        val mainRowId = insertTimelineRow("\$reply", replyDbId, mainChunk())

        stores.timelineEvent.latestThreadReplyId(roomId, rootEventId) shouldBeEqualTo mainRowId

        // The thread chunk is wiped and rebuilt every time the thread is opened.
        stores.timelineEvent.deleteById(threadRowId)
        stores.timelineEvent.latestThreadReplyId(roomId, rootEventId) shouldBeEqualTo mainRowId
    }

    @Test
    fun `latest reply prefers a chunked row over a pending sending row`() {
        val replyDbId = insertReply("\$reply", ts = 2_000L)
        insertTimelineRow("\$reply", replyDbId, chunkId = null)
        val mainRowId = insertTimelineRow("\$reply", replyDbId, mainChunk())

        stores.timelineEvent.latestThreadReplyId(roomId, rootEventId) shouldBeEqualTo mainRowId
    }

    @Test
    fun `latest reply is the newest by timestamp`() {
        val chunkId = mainChunk()
        val oldRow = insertTimelineRow("\$old", insertReply("\$old", ts = 1_000L), chunkId)
        val newRow = insertTimelineRow("\$new", insertReply("\$new", ts = 3_000L), chunkId)

        stores.timelineEvent.latestThreadReplyId(roomId, rootEventId) shouldBeEqualTo newRow
        stores.timelineEvent.deleteById(newRow)
        stores.timelineEvent.latestThreadReplyId(roomId, rootEventId) shouldBeEqualTo oldRow
    }

    private fun insertRoot(): Long = stores.event.insert(EventEntity(
            eventId = rootEventId, roomId = roomId, type = EventType.MESSAGE,
            content = """{"body":"root"}""", sender = "@alice:hs", originServerTs = 1_000L))

    @Test
    fun `marking picks up a root stored after the replies that point at it`() {
        val chunkId = mainChunk()
        val replyRowId = insertTimelineRow("\$reply", insertReply("\$reply", ts = 2_000L), chunkId)
        // The replies landed in an earlier page; nothing could be marked then.
        stores.markThreadRoots(roomId, listOf("\$reply"))

        insertRoot()
        stores.markThreadRoots(roomId, listOf(rootEventId))

        stores.event.getByEventId(rootEventId)!!.let {
            it.isRootThread shouldBe true
            it.numberOfThreads shouldBeEqualTo 1
            it.threadSummaryLatestMessage?.eventId shouldBeEqualTo "\$reply"
        }
        stores.timelineEvent.latestThreadReplyId(roomId, rootEventId) shouldBeEqualTo replyRowId
    }

    @Test
    fun `marking ignores ids that are not thread roots`() {
        insertRoot()

        stores.markThreadRoots(roomId, listOf(rootEventId))

        stores.event.getByEventId(rootEventId)!!.isRootThread shouldBe false
    }

    @Test
    fun `re-marking a root without a resolvable reply keeps the preview it already has`() {
        val rootDbId = insertRoot()
        val replyDbId = insertReply("\$reply", ts = 2_000L)
        val replyRowId = insertTimelineRow("\$reply", replyDbId, mainChunk())

        stores.event.markEventAsRoot(rootDbId, numberOfThreads = 1, latestTimelineId = replyRowId)
        stores.event.markEventAsRoot(rootDbId, numberOfThreads = 2, latestTimelineId = null)

        stores.event.getByEventId(rootEventId)!!.let {
            it.isRootThread shouldBe true
            it.numberOfThreads shouldBeEqualTo 2
            it.threadSummaryLatestMessage?.eventId shouldBeEqualTo "\$reply"
            it.threadSummaryLatestMessage?.senderName shouldBeEqualTo "Bob"
        }

        stores.event.unmarkEventAsRoot(rootDbId)
        stores.event.getByEventId(rootEventId)!!.threadSummaryLatestMessage.shouldBeNull()
    }
}
