/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.state

import kotlinx.coroutines.Dispatchers
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StateEventDataSourceTest {

    private val roomId = "!room:hs"

    private lateinit var driver: FrameworkSqliteDriver
    private lateinit var database: SessionSqlDatabase
    private lateinit var stores: SessionStores
    private lateinit var dataSource: StateEventDataSource

    @Before
    fun setUp() {
        driver = FrameworkSqliteDriver.create(RuntimeEnvironment.getApplication(), name = null, schema = SessionSqlDatabase.Schema)
        database = SessionSqlDatabase(driver)
        stores = SessionStores(database)
        dataSource = StateEventDataSource(database, Dispatchers.Unconfined, stores)

        stateEvent(EventType.STATE_ROOM_POWER_LEVELS, stateKey = "", content = """{"users_default":0}""")
        stateEvent(EventType.STATE_ROOM_CREATE, stateKey = "", content = """{"room_version":"11"}""")
        stateEvent(EventType.STATE_ROOM_TOPIC, stateKey = "", content = """{"topic":"hi"}""")
        // The rows that made a single-type lookup expensive: one per member.
        repeat(50) { stateEvent(EventType.STATE_ROOM_MEMBER, stateKey = "@user$it:hs", content = """{"membership":"join"}""") }
    }

    @After
    fun tearDown() {
        driver.close()
    }

    private fun stateEvent(type: String, stateKey: String, content: String) {
        val eventId = "\$${type}_$stateKey"
        stores.event.insert(
                EventEntity(
                        eventId = eventId,
                        roomId = roomId,
                        type = type,
                        content = content,
                        stateKey = stateKey,
                        sender = "@alice:hs",
                        originServerTs = 1_000L,
                )
        )
        stores.currentStateEvent.upsert(roomId, type, stateKey, eventId, eventId)
    }

    @Test
    fun `single type with an empty state key resolves that event`() {
        val event = dataSource.getStateEvent(roomId, EventType.STATE_ROOM_POWER_LEVELS, QueryStringValue.IsEmpty)

        event?.type shouldBeEqualTo EventType.STATE_ROOM_POWER_LEVELS
        event?.content shouldBeEqualTo mapOf("users_default" to 0.0)
    }

    @Test
    fun `single type with a state key resolves the matching member`() {
        val event = dataSource.getStateEvent(roomId, EventType.STATE_ROOM_MEMBER, QueryStringValue.Equals("@user7:hs"))

        event?.stateKey shouldBeEqualTo "@user7:hs"
    }

    @Test
    fun `a state key that matches nothing resolves to null`() {
        dataSource.getStateEvent(roomId, EventType.STATE_ROOM_MEMBER, QueryStringValue.Equals("@nobody:hs")).shouldBeNull()
    }

    @Test
    fun `a type that the room has no state for resolves to null`() {
        dataSource.getStateEvent(roomId, EventType.STATE_ROOM_AVATAR, QueryStringValue.IsEmpty).shouldBeNull()
    }

    @Test
    fun `several types return every matching event`() {
        val events = dataSource.getStateEvents(
                roomId,
                setOf(EventType.STATE_ROOM_POWER_LEVELS, EventType.STATE_ROOM_CREATE, EventType.STATE_ROOM_TOPIC),
                QueryStringValue.IsEmpty
        )

        events.map { it.type }.toSet() shouldBeEqualTo setOf(
                EventType.STATE_ROOM_POWER_LEVELS,
                EventType.STATE_ROOM_CREATE,
                EventType.STATE_ROOM_TOPIC
        )
    }

    @Test
    fun `no type named returns the whole room state`() {
        val events = dataSource.getStateEvents(roomId, emptySet(), QueryStringValue.IsNotNull)

        events.size shouldBeEqualTo 53
    }

    @Test
    fun `a non empty state key filter keeps only the members`() {
        val events = dataSource.getStateEvents(roomId, emptySet(), QueryStringValue.IsNotEmpty)

        events.size shouldBeEqualTo 50
        events.all { it.type == EventType.STATE_ROOM_MEMBER } shouldBeEqualTo true
    }

    @Test
    fun `another room's state is not returned`() {
        dataSource.getStateEvent("!other:hs", EventType.STATE_ROOM_POWER_LEVELS, QueryStringValue.IsEmpty).shouldBeNull()
    }
}
