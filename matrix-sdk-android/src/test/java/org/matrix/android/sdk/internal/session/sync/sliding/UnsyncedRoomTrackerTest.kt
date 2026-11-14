/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync.sliding

import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.matrix.android.sdk.internal.database.sql.store.SessionStores

class UnsyncedRoomTrackerTest {

    private var stored: String? = null
    private val stores = mockk<SessionStores> {
        every { syncToken.getUnsyncedRooms() } answers { stored }
        every { syncToken.setUnsyncedRooms(any()) } answers { stored = firstArg() }
    }
    private val tracker = UnsyncedRoomTracker(stores)

    @Test
    fun `the first delivery without messages sets a baseline rather than marking the room unread`() {
        tracker.onDeliveredWithoutTimeline(ROOM, bumpStamp = 10, now = 1_000)

        tracker.unseenActivityTs(ROOM).shouldBeNull()
    }

    @Test
    fun `a rising bump stamp marks the room unread at the time it was seen`() {
        tracker.onDeliveredWithoutTimeline(ROOM, bumpStamp = 10, now = 1_000)
        tracker.onDeliveredWithoutTimeline(ROOM, bumpStamp = 10, now = 2_000)
        tracker.unseenActivityTs(ROOM).shouldBeNull()

        tracker.onDeliveredWithoutTimeline(ROOM, bumpStamp = 11, now = 3_000)
        tracker.onDeliveredWithoutTimeline(ROOM, bumpStamp = null, now = 4_000)

        tracker.unseenActivityTs(ROOM) shouldBeEqualTo 3_000L
    }

    @Test
    fun `the next timeline is flagged once and ends the tracking`() {
        tracker.onDeliveredWithoutTimeline(ROOM, bumpStamp = 10, now = 1_000)

        tracker.onDeliveredWithTimeline(ROOM) shouldBe true
        tracker.onDeliveredWithTimeline(ROOM) shouldBe false
        tracker.onDeliveredWithTimeline(OTHER_ROOM) shouldBe false
    }

    @Test
    fun `a replayed response finds the rooms it had un-tracked still tracked`() {
        tracker.onDeliveredWithoutTimeline(ROOM, bumpStamp = 10, now = 1_000)
        tracker.commit()

        tracker.onDeliveredWithTimeline(ROOM) shouldBe true
        tracker.rollback()

        tracker.onDeliveredWithTimeline(ROOM) shouldBe true
    }

    @Test
    fun `committed rooms survive a restart`() {
        tracker.onDeliveredWithoutTimeline(ROOM, bumpStamp = 10, now = 1_000)
        tracker.onDeliveredWithoutTimeline(ROOM, bumpStamp = 12, now = 2_000)
        tracker.commit()

        val restarted = UnsyncedRoomTracker(stores)

        restarted.unseenActivityTs(ROOM) shouldBeEqualTo 2_000L
        restarted.onDeliveredWithTimeline(ROOM) shouldBe true
    }

    @Test
    fun `marking the room read clears its unread marker but keeps the gap`() {
        tracker.onDeliveredWithoutTimeline(ROOM, bumpStamp = 10, now = 1_000)
        tracker.onDeliveredWithoutTimeline(ROOM, bumpStamp = 12, now = 2_000)
        tracker.commit()

        tracker.clearActivity(ROOM)
        tracker.unseenActivityTs(ROOM).shouldBeNull()
        // Survives a failed response, and is written by the next commit even with nothing else changed.
        tracker.rollback()
        tracker.unseenActivityTs(ROOM).shouldBeNull()
        tracker.commit()

        val restarted = UnsyncedRoomTracker(stores)
        restarted.unseenActivityTs(ROOM).shouldBeNull()
        restarted.onDeliveredWithTimeline(ROOM) shouldBe true
    }

    private companion object {
        const val ROOM = "!room:example.org"
        const val OTHER_ROOM = "!other:example.org"
    }
}
