/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.typing

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.internal.session.room.summary.RoomSummaryPreviewInvalidation

class DefaultTypingUsersTrackerTest {

    private val invalidated = mutableListOf<String>()
    private val tracker = DefaultTypingUsersTracker(
            RoomSummaryPreviewInvalidation().also { it.register { roomId -> invalidated.add(roomId) } }
    )

    private val bareId = SenderInfo("@alice:hs", displayName = null, isUniqueDisplayName = true, avatarUrl = null)
    private val resolved = bareId.copy(displayName = "Alice")

    @Test
    fun `a changed typing list evicts the room's memoized summary`() {
        tracker.setTypingUsersFromRoom("!room", listOf(bareId)) shouldBeEqualTo true
        tracker.setTypingUsersFromRoom("!room", listOf(resolved)) shouldBeEqualTo true

        tracker.getTypingUsers("!room") shouldBeEqualTo listOf(resolved)
        invalidated shouldBeEqualTo listOf("!room", "!room")
    }

    @Test
    fun `an unchanged typing list leaves the memo alone`() {
        tracker.setTypingUsersFromRoom("!room", listOf(resolved))
        invalidated.clear()

        tracker.setTypingUsersFromRoom("!room", listOf(resolved)) shouldBeEqualTo false
        invalidated shouldBeEqualTo emptyList()
    }
}
