/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.membership

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType

class LoadRoomMembersOrderingTest {

    private fun memberEvent(userId: String) = Event(
            type = EventType.STATE_ROOM_MEMBER,
            eventId = "\$evt_$userId",
            stateKey = userId,
    )

    private val events = listOf("@a:x", "@b:x", "@c:x", "@d:x").map { memberEvent(it) }

    @Test
    fun `receipt holders are moved to the front`() {
        val sorted = events.receiptHoldersFirst(setOf("@c:x", "@d:x"))

        sorted.map { it.stateKey } shouldBeEqualTo listOf("@c:x", "@d:x", "@a:x", "@b:x")
    }

    @Test
    fun `relative order is preserved within each group`() {
        val sorted = events.receiptHoldersFirst(setOf("@d:x", "@b:x"))

        sorted.map { it.stateKey } shouldBeEqualTo listOf("@b:x", "@d:x", "@a:x", "@c:x")
    }

    @Test
    fun `no receipts leaves the list untouched`() {
        events.receiptHoldersFirst(emptySet()) shouldBeEqualTo events
    }
}
