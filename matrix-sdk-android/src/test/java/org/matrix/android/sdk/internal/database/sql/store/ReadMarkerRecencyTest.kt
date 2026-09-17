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
class ReadMarkerRecencyTest {

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
    fun `no marker means nothing is behind it`() {
        add("\$a", ts = 10 * HOUR)

        stores.isReadMarkerMoreRecent(ROOM_ID, "\$a") shouldBeEqualTo false
    }

    @Test
    fun `a marker on an older event is not more recent`() {
        add("\$old", ts = 10 * HOUR)
        add("\$new", ts = 20 * HOUR)
        stores.readMarker.upsert(ROOM_ID, "\$old")

        stores.isReadMarkerMoreRecent(ROOM_ID, "\$new") shouldBeEqualTo false
    }

    @Test
    fun `a marker on a newer event is more recent`() {
        add("\$old", ts = 10 * HOUR)
        add("\$new", ts = 20 * HOUR)
        stores.readMarker.upsert(ROOM_ID, "\$new")

        stores.isReadMarkerMoreRecent(ROOM_ID, "\$old") shouldBeEqualTo true
    }

    @Test
    fun `a marker on the event itself counts as already there`() {
        add("\$a", ts = 10 * HOUR)
        stores.readMarker.upsert(ROOM_ID, "\$a")

        stores.isReadMarkerMoreRecent(ROOM_ID, "\$a") shouldBeEqualTo true
    }

    /** Same timestamp is decided by event id, the same tie-break the ordering uses. */
    @Test
    fun `events sharing a timestamp are still ordered`() {
        add("\$aaa", ts = 10 * HOUR)
        add("\$bbb", ts = 10 * HOUR)
        stores.readMarker.upsert(ROOM_ID, "\$bbb")

        stores.isReadMarkerMoreRecent(ROOM_ID, "\$aaa") shouldBeEqualTo true
        stores.readMarker.upsert(ROOM_ID, "\$aaa")
        stores.isReadMarkerMoreRecent(ROOM_ID, "\$bbb") shouldBeEqualTo false
    }

    @Test
    fun `a marker on an event this client never stored blocks nothing`() {
        add("\$a", ts = 10 * HOUR)
        stores.readMarker.upsert(ROOM_ID, "\$unknown")

        stores.isReadMarkerMoreRecent(ROOM_ID, "\$a") shouldBeEqualTo false
    }

    @Test
    fun `the newest stored event is the live chunk's newest, not its first row`() {
        add("\$a", ts = 10 * HOUR)
        add("\$c", ts = 30 * HOUR)
        add("\$b", ts = 20 * HOUR)

        stores.latestSyncedEventId(ROOM_ID) shouldBeEqualTo "\$c"
    }

    private fun add(eventId: String, ts: Long) {
        val entity = EventEntity(eventId = eventId, roomId = ROOM_ID, type = EventType.MESSAGE, sender = "@a:hs", originServerTs = ts)
        val dbId = stores.event.insert(entity)
        stores.timelineWriter.addTimelineEvent(
                chunkId = chunkId, roomId = ROOM_ID, eventDbId = dbId, event = entity, isLastForward = true,
        )
    }
}
