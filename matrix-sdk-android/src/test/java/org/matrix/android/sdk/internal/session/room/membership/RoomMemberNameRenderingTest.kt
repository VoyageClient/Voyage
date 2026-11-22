/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.membership

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.internal.database.mapper.ThreadSummaryMapper
import org.matrix.android.sdk.internal.database.mapper.TimelineEventMapper
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.model.threads.ThreadSummaryEntity
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.session.room.timeline.PaginationResponse
import org.matrix.android.sdk.internal.session.room.uploads.DefaultGetUploadsTask
import org.matrix.android.sdk.internal.session.room.uploads.GetUploadsTask
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoomMemberNameRenderingTest {
    private val db = FakeSessionDatabase()
    private val roomId = "!room:example.org"
    private val alice = "@alice:example.org"
    private val namesake = "@namesake:example.org"
    private val cache = AmbiguousDisplayNameCache(db.stores)
    private val handler = SqlRoomMemberEventHandler("@me:example.org", mockk(relaxed = true), cache)
    private val mapper = TimelineEventMapper(mockk(relaxed = true), mockk(relaxed = true), cache)
    private val root = EventEntity(eventId = "\$message", roomId = roomId, sender = alice, type = EventType.MESSAGE)
    private val timeline = TimelineEventEntity(
            eventId = root.eventId, roomId = roomId, root = root,
            senderName = "Alice", isUniqueDisplayName = false,
    )

    @After
    fun tearDown() = db.close()

    @Test
    fun `leaving without a name change refreshes cached timeline names`() {
        member(alice, Membership.JOIN)
        member(namesake, Membership.JOIN)
        assertEquals("Alice ($alice)", mapper.map(timeline, false).senderInfo.disambiguatedDisplayName)
        val generation = cache.generation

        member(namesake, Membership.LEAVE)

        assertTrue(cache.generation > generation)
        assertEquals("Alice", mapper.map(timeline, false).senderInfo.disambiguatedDisplayName)
        assertEquals(Membership.LEAVE, db.stores.roomMember.getByRoomAndUser(roomId, namesake)?.membership)
        member(namesake, Membership.JOIN)
        assertEquals("Alice ($alice)", mapper.map(timeline, false).senderInfo.disambiguatedDisplayName)
    }

    @Test
    fun `banned namesakes stop counting and invited namesakes count`() {
        member(alice, Membership.JOIN)
        member(namesake, Membership.INVITE)
        assertTrue(cache.isAmbiguous(roomId, "Alice", alice) == true)

        member(namesake, Membership.BAN)

        assertFalse(cache.isAmbiguous(roomId, "Alice", alice) == true)
    }

    @Test
    fun `thread sender names use the current shared index instead of stored flags`() {
        member(alice, Membership.JOIN)
        member(namesake, Membership.LEAVE)
        val threads = ThreadSummaryMapper(cache)
        val summary = ThreadSummaryEntity(
                rootThreadEventId = root.eventId, rootThreadEventEntity = root, latestThreadEventEntity = root,
                rootThreadSenderName = "Alice", latestThreadSenderName = "Alice",
                rootThreadIsUniqueDisplayName = false, latestThreadIsUniqueDisplayName = false,
        )

        val mapped = threads.map(summary)

        assertEquals("Alice", mapped.rootThreadSenderInfo.disambiguatedDisplayName)
        assertEquals("Alice", mapped.latestThreadSenderInfo.disambiguatedDisplayName)
        member(namesake, Membership.JOIN)
        assertEquals("Alice ($alice)", threads.map(summary).rootThreadSenderInfo.disambiguatedDisplayName)
    }

    @Test
    fun `peeked state uses the same active member name rules`() {
        val state = mapOf(
                alice to RoomMemberContent(membership = Membership.JOIN, displayName = "Alice"),
                namesake to RoomMemberContent(membership = Membership.LEAVE, displayName = "Alice"),
        )
        assertFalse(RoomMemberNameIndex.fromState(state).isAmbiguous("Alice", alice))
        assertTrue(RoomMemberNameIndex.fromState(state).isAmbiguous("Alice", namesake))
    }

    @Test
    fun `first nameless member invalidates the unloaded room result`() {
        assertEquals(null, cache.isAmbiguous(roomId, "Alice"))
        member(alice, Membership.JOIN, null)
        assertEquals(false, cache.isAmbiguous(roomId, "Alice"))
    }

    @Test
    fun `uploads and timeline use the same cached name result after membership changes`() = runBlocking {
        member(alice, Membership.JOIN)
        member(namesake, Membership.LEAVE)
        val event = Event(
                eventId = "\$image", roomId = roomId, senderId = alice, type = EventType.MESSAGE,
                content = mapOf("msgtype" to "m.image", "body" to "image.jpg", "url" to "mxc://example.org/image"),
        )
        val api = mockk<RoomAPI> {
            coEvery { getRoomMessagesFrom(any(), any(), any(), any(), any()) } returns PaginationResponse(chunk = listOf(event))
        }
        val task = DefaultGetUploadsTask(
                roomAPI = api, tokenStore = mockk(relaxed = true), stores = db.stores,
                eventIndexer = mockk(relaxed = true), indexStore = mockk(relaxed = true),
                cryptoService = mockk(relaxed = true), globalErrorReceiver = mockk(relaxed = true),
                coroutineDispatchers = mockk { every { io } returns Dispatchers.Unconfined },
                eventDataSource = mockk { every { getTimelineEvent(any(), any()) } returns null },
                getEventTask = mockk(relaxed = true), ambiguousDisplayNameCache = cache,
        )
        val params = GetUploadsTask.Params(roomId, false, 10, null)

        val unique = task.execute(params).uploadEvents.single().senderInfo
        assertEquals(mapper.map(timeline, false).senderInfo.disambiguatedDisplayName, unique.disambiguatedDisplayName)
        assertEquals("Alice", unique.disambiguatedDisplayName)

        member(namesake, Membership.JOIN)

        val ambiguous = task.execute(params).uploadEvents.single().senderInfo
        assertEquals(mapper.map(timeline, false).senderInfo.disambiguatedDisplayName, ambiguous.disambiguatedDisplayName)
        assertEquals("Alice ($alice)", ambiguous.disambiguatedDisplayName)
    }

    private fun member(userId: String, membership: Membership, name: String? = "Alice") {
        val content = mutableMapOf<String, Any>("membership" to membership.name.lowercase())
        if (name != null) content["displayname"] = name
        handler.handle(db.stores, roomId, Event(
                eventId = "\$member_${membership.name}_$userId", roomId = roomId,
                type = EventType.STATE_ROOM_MEMBER, senderId = userId, stateKey = userId, content = content,
        ), isInitialSync = true)
    }
}
