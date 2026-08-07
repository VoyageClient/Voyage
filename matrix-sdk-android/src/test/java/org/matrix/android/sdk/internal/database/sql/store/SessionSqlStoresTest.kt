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
import org.amshove.kluent.shouldContainSame
import org.amshove.kluent.shouldHaveSize
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.HomeServerCapabilitiesEntity
import org.matrix.android.sdk.internal.database.model.PendingThreePidEntity
import org.matrix.android.sdk.internal.database.model.PusherEntity
import org.matrix.android.sdk.internal.database.model.ReadReceiptEntity
import org.matrix.android.sdk.internal.database.model.RoomEntity
import org.matrix.android.sdk.internal.database.model.RoomMembersLoadStatusType
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sqldelight.FrameworkSqliteDriver
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SessionSqlStoresTest {

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
    fun `pending three pids replace-all round-trip`() {
        stores.threePid.getPendingThreePids().shouldHaveSize(0)

        stores.threePid.addPendingThreePid(PendingThreePidEntity(
                email = "a@b.c", msisdn = null, clientSecret = "secret", sendAttempt = 1, sid = "sid1", submitUrl = null))

        stores.threePid.getPendingThreePids().single().let {
            it.email shouldBeEqualTo "a@b.c"
            it.clientSecret shouldBeEqualTo "secret"
            it.sendAttempt shouldBeEqualTo 1
            it.sid shouldBeEqualTo "sid1"
        }

        stores.threePid.clearPendingThreePids()
        stores.threePid.getPendingThreePids().shouldHaveSize(0)
    }

    @Test
    fun `home server capabilities round-trip`() {
        stores.homeServerCapabilities.get().shouldBeNull()

        stores.homeServerCapabilities.upsert(HomeServerCapabilitiesEntity(
                maxUploadFileSize = 42L,
                canUseThreading = true,
                lastUpdatedTimestamp = 123L,
        ))

        stores.homeServerCapabilities.get()!!.let {
            it.maxUploadFileSize shouldBeEqualTo 42L
            it.canUseThreading shouldBe true
            it.lastUpdatedTimestamp shouldBeEqualTo 123L
        }
    }

    @Test
    fun `home server capabilities upsert replaces the single row`() {
        stores.homeServerCapabilities.upsert(HomeServerCapabilitiesEntity(maxUploadFileSize = 1L))
        stores.homeServerCapabilities.upsert(HomeServerCapabilitiesEntity(maxUploadFileSize = 2L))

        stores.homeServerCapabilities.get()!!.maxUploadFileSize shouldBeEqualTo 2L
    }

    @Test
    fun `pushers insert, lookup by key and delete`() {
        stores.pushers.insert(PusherEntity(pushKey = "key1", appId = "im.app", kind = "http"))
        stores.pushers.insert(PusherEntity(pushKey = "key2", appId = "im.app", kind = "http"))

        stores.pushers.getAll().shouldHaveSize(2)
        stores.pushers.getByPushKey("key1").single().appId shouldBeEqualTo "im.app"

        stores.pushers.deleteByPushKey("key1")
        stores.pushers.getByPushKey("key1").shouldHaveSize(0)
        stores.pushers.getAll().shouldHaveSize(1)
    }

    @Test
    fun `read receipt upsert is keyed by primary key`() {
        stores.readReceipt.upsertReceipt(ReadReceiptEntity(
                primaryKey = "!r:hs_@a:hs", eventId = "\$e1", roomId = "!r:hs", userId = "@a:hs", originServerTs = 100.0))

        stores.readReceipt.getReceipt("!r:hs", "@a:hs", threadId = null)!!.eventId shouldBeEqualTo "\$e1"

        // same primary key -> moves the user's receipt forward, not a second row
        stores.readReceipt.upsertReceipt(ReadReceiptEntity(
                primaryKey = "!r:hs_@a:hs", eventId = "\$e2", roomId = "!r:hs", userId = "@a:hs", originServerTs = 200.0))

        stores.readReceipt.getReceipt("!r:hs", "@a:hs", threadId = null)!!.eventId shouldBeEqualTo "\$e2"
    }

    @Test
    fun `room membership and load status round-trip`() {
        stores.room.upsert(RoomEntity(roomId = "!a:hs").apply {
            membership = Membership.JOIN
            membersLoadStatus = RoomMembersLoadStatusType.LOADED
        })
        stores.room.upsert(RoomEntity(roomId = "!b:hs").apply {
            membership = Membership.INVITE
        })

        stores.room.get("!a:hs")!!.let {
            it.membership shouldBeEqualTo Membership.JOIN
            it.membersLoadStatus shouldBeEqualTo RoomMembersLoadStatusType.LOADED
        }
        stores.room.getByMemberships(listOf(Membership.JOIN)).map { it.roomId } shouldContainSame listOf("!a:hs")
    }

    // poll history: timeline_event JOIN event filtered by type IN (...) and origin_server_ts > since
    @Test
    fun `getByRoomTypesAfterTs joins events of the given types newer than the timestamp`() {
        insertTimelineEvent("\$poll1", EventType.POLL_START.unstable, ts = 2_000L)
        insertTimelineEvent("\$poll2", EventType.POLL_START.unstable, ts = 500L)   // too old
        insertTimelineEvent("\$msg", EventType.MESSAGE, ts = 3_000L)               // wrong type

        val result = stores.timelineEvent.getByRoomTypesAfterTs("!room:hs", listOf(EventType.POLL_START.unstable), ts = 1_000L)

        result.map { it.eventId } shouldContainSame listOf("\$poll1")
    }

    private fun insertTimelineEvent(eventId: String, type: String, ts: Long) {
        val dbId = stores.event.insert(EventEntity(
                eventId = eventId, roomId = "!room:hs", type = type, sender = "@a:hs", originServerTs = ts))
        stores.timelineEvent.insert(
                TimelineEventEntity(eventId = eventId, roomId = "!room:hs", displayIndex = 0),
                chunkId = 1L,
                rootEventDbId = dbId,
        )
    }
}
