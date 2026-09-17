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
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private const val ROOM_ID = "!room:hs"
private const val ME = "@me:hs"
private const val THEM = "@them:hs"
private const val ROOT = "\$thread-root"
private const val HOUR = 60 * 60 * 1000L

@RunWith(RobolectricTestRunner::class)
class ThreadUnreadCountTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var stores: SessionStores
    private var chunkId: Long = 0

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = SessionSqlDatabase.Schema)
        stores = SessionStores(SessionSqlDatabase(driver))
        chunkId = stores.chunk.insert(ROOM_ID, null, null, true, false, null, false)
        add("\$read", ts = 10 * HOUR)
        receipt("\$read", ts = 10 * HOUR)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a reply inside a thread does not make the room unread`() {
        add("\$reply", ts = 20 * HOUR, threadRootId = ROOT)

        counts(threadsEnabled = true).notificationCount shouldBeEqualTo 0
    }

    @Test
    fun `with threads off a thread reply is just another message`() {
        add("\$reply", ts = 20 * HOUR, threadRootId = ROOT)

        counts(threadsEnabled = false).notificationCount shouldBeEqualTo 1
    }

    @Test
    fun `a message in the room itself still counts`() {
        add("\$msg", ts = 20 * HOUR)
        add("\$reply", ts = 21 * HOUR, threadRootId = ROOT)

        counts(threadsEnabled = true).notificationCount shouldBeEqualTo 1
    }

    @Test
    fun `a thread reply that mentions us notifies and highlights`() {
        add("\$reply", ts = 20 * HOUR, threadRootId = ROOT, mentionsMe = true)

        val counts = counts(threadsEnabled = true)

        counts.highlightCount shouldBeEqualTo 1
        counts.notificationCount shouldBeEqualTo 1
    }

    /** The mention is already in the message count there; adding it again would count it twice. */
    @Test
    fun `a mentioning thread reply is counted once with threads off`() {
        add("\$reply", ts = 20 * HOUR, threadRootId = ROOT, mentionsMe = true)

        counts(threadsEnabled = false).notificationCount shouldBeEqualTo 1
    }

    @Test
    fun `our own thread reply counts for nothing`() {
        add("\$mine", ts = 20 * HOUR, sender = ME, threadRootId = ROOT)

        counts(threadsEnabled = true).notificationCount shouldBeEqualTo 0
    }

    @Test
    fun `an edit does not make the room unread`() {
        add("\$edit", ts = 20 * HOUR, relationType = "m.replace")

        counts(threadsEnabled = true).notificationCount shouldBeEqualTo 0
    }

    private fun counts(threadsEnabled: Boolean) = stores.localUnreadCounts(ME, ROOM_ID, threadsEnabled)

    private fun add(
            eventId: String,
            ts: Long,
            sender: String = THEM,
            threadRootId: String? = null,
            mentionsMe: Boolean = false,
            relationType: String? = null,
    ) {
        val relation = relationType ?: threadRootId?.let { "m.thread" }
        val relatesTo = relation?.let { ""","m.relates_to":{"rel_type":"$it","event_id":"${threadRootId ?: "\$target"}"}""" }.orEmpty()
        val mentions = if (mentionsMe) ""","m.mentions":{"user_ids":["$ME"]}""" else ""
        val entity = EventEntity(
                eventId = eventId,
                roomId = ROOM_ID,
                type = EventType.MESSAGE,
                sender = sender,
                originServerTs = ts,
                content = """{"msgtype":"m.text","body":"hi"$relatesTo$mentions}""",
                rootThreadEventId = threadRootId,
        )
        val dbId = stores.event.insert(entity)
        stores.timelineWriter.addTimelineEvent(
                chunkId = chunkId, roomId = ROOM_ID, eventDbId = dbId, event = entity, isLastForward = true,
        )
    }

    private fun receipt(eventId: String, ts: Long) {
        stores.readReceipt.upsertReceipt(
                ReadReceiptEntity(
                        primaryKey = "$ROOM_ID$ME",
                        eventId = eventId,
                        roomId = ROOM_ID,
                        userId = ME,
                        threadId = ReadService.THREAD_ID_MAIN,
                        originServerTs = ts.toDouble(),
                )
        )
    }
}
