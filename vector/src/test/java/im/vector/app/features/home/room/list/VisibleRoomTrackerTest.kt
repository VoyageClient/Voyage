/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class VisibleRoomTrackerTest {
    @Test
    fun `a replacement model cannot release a visible room`() {
        val tracker = VisibleRoomTracker()

        tracker.update("room", true) shouldBeEqualTo true
        tracker.update("room", true) shouldBeEqualTo false
        tracker.update("room", false) shouldBeEqualTo false
        tracker.roomIds() shouldBeEqualTo setOf("room")
        tracker.update("room", false) shouldBeEqualTo true
        tracker.roomIds() shouldBeEqualTo emptySet()
    }
}
