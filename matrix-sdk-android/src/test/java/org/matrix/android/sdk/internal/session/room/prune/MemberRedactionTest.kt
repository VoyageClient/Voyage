/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.prune

import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.internal.database.mapper.EventMapper
import org.matrix.android.sdk.internal.database.mapper.ReadReceiptsSummaryMapper
import org.matrix.android.sdk.internal.database.model.ReadReceiptEntity
import org.matrix.android.sdk.internal.database.model.ReadReceiptsSummaryEntity
import org.matrix.android.sdk.internal.database.model.UserEntity
import org.matrix.android.sdk.internal.session.room.membership.SqlRoomMemberEventHandler
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MemberRedactionTest {

    private val db = FakeSessionDatabase()
    private val roomId = "!room:example.org"
    private val alice = "@alice:example.org"
    private val memberHandler = SqlRoomMemberEventHandler("@me:example.org", mockk(relaxed = true), mockk(relaxed = true))
    private val processor = RedactionEventProcessor(
            roomSummaryUpdater = mockk(relaxed = true),
            eventIndexer = mockk(relaxed = true),
            previewInvalidation = mockk(relaxed = true),
            timelineRedactionSignal = mockk(relaxed = true),
            pollResponseSummaryRefresher = mockk(relaxed = true),
            roomMemberEventHandler = memberHandler,
    )

    @After
    fun tearDown() = db.close()

    @Test
    fun `redacting the current member event clears the stored profile`() {
        join("\$join1", "Alice", "mxc://example.org/alice")

        processor.prune(db.stores, redaction("\$join1"))

        val member = db.stores.roomMember.getByRoomAndUser(roomId, alice)!!
        assertNull(member.displayName)
        assertNull(member.avatarUrl)
        assertEquals(Membership.JOIN, member.membership)
    }

    @Test
    fun `redacting a superseded member event leaves the stored profile`() {
        join("\$join1", "Old name", null)
        join("\$join2", "Alice", "mxc://example.org/alice")

        processor.prune(db.stores, redaction("\$join1"))

        val member = db.stores.roomMember.getByRoomAndUser(roomId, alice)!!
        assertEquals("Alice", member.displayName)
        assertEquals("mxc://example.org/alice", member.avatarUrl)
    }

    @Test
    fun `receipt of a nameless joined member does not fall back to the global profile`() {
        db.stores.user.upsertUser(UserEntity(alice, "Alice", "mxc://example.org/alice"))
        join("\$join1", "Alice", "mxc://example.org/alice")
        processor.prune(db.stores, redaction("\$join1"))

        val receipt = ReadReceiptsSummaryMapper(db.stores).map(
                ReadReceiptsSummaryEntity("\$read", roomId, mutableListOf(ReadReceiptEntity("key", "\$read", roomId, alice)))
        ).single()

        assertNull(receipt.roomMember.displayName)
        assertNull(receipt.roomMember.avatarUrl)
    }

    private fun join(eventId: String, displayName: String?, avatarUrl: String?) {
        val event = Event(
                type = EventType.STATE_ROOM_MEMBER,
                eventId = eventId,
                roomId = roomId,
                senderId = alice,
                stateKey = alice,
                content = listOfNotNull(
                        "membership" to "join",
                        displayName?.let { "displayname" to it },
                        avatarUrl?.let { "avatar_url" to it },
                ).toMap(),
        )
        db.stores.event.insert(EventMapper.map(event, roomId))
        db.stores.currentStateEvent.upsert(roomId, EventType.STATE_ROOM_MEMBER, alice, eventId, eventId)
        memberHandler.handle(db.stores, roomId, event, isInitialSync = false)
    }

    private fun redaction(target: String) = Event(
            type = EventType.REDACTION,
            eventId = "\$redaction",
            roomId = roomId,
            senderId = alice,
            redacts = target,
    )
}
