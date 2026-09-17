/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sqldelight

import app.cash.sqldelight.Query
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private const val ROOM_ID = "!room:hs"

@RunWith(RobolectricTestRunner::class)
class SendingRowNotificationTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var database: SessionSqlDatabase
    private lateinit var stores: SessionStores
    private var notifications = 0
    private val listener = Query.Listener { notifications++ }

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = SessionSqlDatabase.Schema)
        database = SessionSqlDatabase(driver)
        stores = SessionStores(database)
        database.timelineEventQueries.selectSendingByRoom(ROOM_ID).addListener(listener)
    }

    @After
    fun tearDown() {
        database.timelineEventQueries.selectSendingByRoom(ROOM_ID).removeListener(listener)
        driver.close()
    }

    @Test
    fun `queueing a message outside a transaction wakes the timeline`() {
        insertSendingRow("\$echo1")

        notifications shouldBeEqualTo 1
    }

    @Test
    fun `queueing a message inside a transaction wakes the timeline once it commits`() {
        database.transaction { insertSendingRow("\$echo1") }

        notifications shouldBeEqualTo 1
    }

    @Test
    fun `the row really is readable when the wake-up arrives`() {
        var seenWhenNotified = -1
        val counting = Query.Listener { seenWhenNotified = stores.timelineEvent.getSendingByRoom(ROOM_ID).size }
        database.timelineEventQueries.selectSendingByRoom(ROOM_ID).addListener(counting)

        database.transaction { insertSendingRow("\$echo1") }

        seenWhenNotified shouldBeEqualTo 1
        database.timelineEventQueries.selectSendingByRoom(ROOM_ID).removeListener(counting)
    }

    @Test
    fun `deleting the echo when its synced copy arrives also wakes the timeline`() {
        database.transaction { insertSendingRow("\$echo1") }
        notifications = 0

        database.transaction { stores.timelineEvent.deleteSending(ROOM_ID, "\$echo1") }

        notifications shouldBeEqualTo 1
        stores.timelineEvent.getSendingByRoom(ROOM_ID).size shouldBeEqualTo 0
    }

    private fun insertSendingRow(eventId: String) {
        val entity = EventEntity(eventId = eventId, roomId = ROOM_ID, type = EventType.MESSAGE, sender = "@me:hs", originServerTs = 0L)
        val dbId = stores.event.insert(entity)
        stores.timelineEvent.insert(
                TimelineEventEntity(eventId = eventId, roomId = ROOM_ID, localId = 1L, ts = 0L),
                chunkId = null,
                rootEventDbId = dbId,
        )
    }
}
