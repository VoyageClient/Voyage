/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync.handler.room

import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.matrix.android.sdk.internal.session.room.read.FullyReadContent
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private const val ROOM_ID = "!room:example.org"
private const val HOUR = 60 * 60 * 1000L

@RunWith(RobolectricTestRunner::class)
class RoomFullyReadHandlerTest {

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var stores: SessionStores
    private val handler = SqlRoomFullyReadHandler()
    private var chunkId: Long = 0

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = SessionSqlDatabase.Schema)
        stores = SessionStores(SessionSqlDatabase(driver))
        chunkId = stores.chunk.insert(ROOM_ID, null, null, true, false, null, false)
        add("\$old", ts = 10 * HOUR)
        add("\$new", ts = 20 * HOUR)
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a newer marker moves it forward`() {
        handler.handle(stores, ROOM_ID, FullyReadContent("\$old"))
        handler.handle(stores, ROOM_ID, FullyReadContent("\$new"))

        stores.readMarker.get(ROOM_ID) shouldBeEqualTo "\$new"
    }

    @Test
    fun `an older marker does not move it back`() {
        handler.handle(stores, ROOM_ID, FullyReadContent("\$new"))
        handler.handle(stores, ROOM_ID, FullyReadContent("\$old"))

        stores.readMarker.get(ROOM_ID) shouldBeEqualTo "\$new"
    }

    @Test
    fun `a marker that cannot be ordered is taken`() {
        handler.handle(stores, ROOM_ID, FullyReadContent("\$new"))
        handler.handle(stores, ROOM_ID, FullyReadContent("\$unknown"))

        stores.readMarker.get(ROOM_ID) shouldBeEqualTo "\$unknown"
    }

    private fun add(eventId: String, ts: Long) {
        val entity = EventEntity(eventId = eventId, roomId = ROOM_ID, type = EventType.MESSAGE, sender = "@alice:example.org", originServerTs = ts)
        val dbId = stores.event.insert(entity)
        stores.timelineWriter.addTimelineEvent(
                chunkId = chunkId, roomId = ROOM_ID, eventDbId = dbId, event = entity, isLastForward = true,
        )
    }
}
