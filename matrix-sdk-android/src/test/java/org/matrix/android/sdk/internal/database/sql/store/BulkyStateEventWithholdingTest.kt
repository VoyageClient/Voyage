/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.internal.database.BulkyStateEvents
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private const val ROOM_ID = "!room:hs"
private const val SENDER = "@them:hs"

/**
 * The timeline withholds the bulky JSON of a few state types instead of parsing it on every room open.
 * These pin the size gate: big listed events are withheld, small ones and unlisted types are not.
 */
@RunWith(RobolectricTestRunner::class)
class BulkyStateEventWithholdingTest {

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
    fun `a big listed state event has its content withheld from the timeline`() {
        add("\$acl", type = EventType.STATE_ROOM_SERVER_ACL, content = big(), prevContent = big(), unsigned = big())

        val entity = timelineRead("\$acl")

        entity.contentWithheld shouldBe true
        entity.content shouldBe null
        entity.prevContent shouldBe null
        entity.unsignedData shouldBe null
    }

    @Test
    fun `a small listed state event keeps its content`() {
        add("\$acl", type = EventType.STATE_ROOM_SERVER_ACL, content = """{"deny":["evil.example"]}""")

        val entity = timelineRead("\$acl")

        entity.contentWithheld shouldBe false
        entity.content shouldNotBe null
    }

    @Test
    fun `a big unlisted event keeps its content`() {
        add("\$msg", type = EventType.MESSAGE, content = big())

        val entity = timelineRead("\$msg")

        entity.contentWithheld shouldBe false
        entity.content shouldNotBe null
    }

    /** Room settings and devtools read through the ordinary path, which must still see everything. */
    @Test
    fun `the ordinary read still returns a big listed event in full`() {
        val dbId = add("\$acl", type = EventType.STATE_ROOM_SERVER_ACL, content = big(), prevContent = big(), unsigned = big())

        val entity = stores.event.getByIds(listOf(dbId)).getValue(dbId)

        entity.contentWithheld shouldBe false
        entity.content?.length shouldBeEqualTo big().length
        entity.prevContent shouldNotBe null
        entity.unsignedData shouldNotBe null
    }

    @Test
    fun `withholding is per field, so a big acl with a small prev keeps the prev`() {
        add("\$acl", type = EventType.STATE_ROOM_SERVER_ACL, content = big(), prevContent = """{"deny":[]}""")

        val entity = timelineRead("\$acl")

        entity.contentWithheld shouldBe true
        entity.content shouldBe null
        entity.prevContent shouldNotBe null
    }

    private fun timelineRead(eventId: String): EventEntity =
            stores.timelineEvent.getByChunk(chunkId).first { it.eventId == eventId }.root!!

    private fun big() = "{\"deny\":[\"" + "x".repeat(BulkyStateEvents.MAX_INLINE_LENGTH.toInt() + 1) + "\"]}"

    private fun add(
            eventId: String,
            type: String,
            content: String? = null,
            prevContent: String? = null,
            unsigned: String? = null,
    ): Long {
        val entity = EventEntity(
                eventId = eventId,
                roomId = ROOM_ID,
                type = type,
                sender = SENDER,
                originServerTs = 1_000L,
                stateKey = "".takeIf { type != EventType.MESSAGE },
                content = content,
                prevContent = prevContent,
                unsignedData = unsigned,
        )
        val dbId = stores.event.insert(entity)
        stores.timelineWriter.addTimelineEvent(
                chunkId = chunkId, roomId = ROOM_ID, eventDbId = dbId, event = entity, isLastForward = true,
        )
        return dbId
    }
}
