/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync.sliding

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.internal.session.sync.sliding.SlidingSyncRoomSubscriptions.Depth

class SlidingSyncRoomSubscriptionsTest {

    @Test
    fun `opening a room notifies once`() {
        val subscriptions = SlidingSyncRoomSubscriptions()
        var notifications = 0
        subscriptions.addListener { notifications++ }

        subscriptions.acquire("room-a")
        subscriptions.acquire("room-a")

        notifications shouldBeEqualTo 1
    }

    @Test
    fun `a viewport update does not interrupt the current sync`() {
        val subscriptions = SlidingSyncRoomSubscriptions()
        var notifications = 0
        subscriptions.addListener { notifications++ }

        subscriptions.update(added = setOf("room-a", "room-b"), removed = emptySet())

        notifications shouldBeEqualTo 0
        subscriptions.snapshot().toSortedMap() shouldBeEqualTo sortedMapOf("room-a" to Depth.VISIBLE, "room-b" to Depth.VISIBLE)
    }

    @Test
    fun `the open room keeps its depth while it is also visible in the list`() {
        val subscriptions = SlidingSyncRoomSubscriptions()
        subscriptions.acquire("room-a")

        subscriptions.update(added = setOf("room-a", "room-b"), removed = emptySet())
        subscriptions.snapshot().toSortedMap() shouldBeEqualTo sortedMapOf("room-a" to Depth.OPEN, "room-b" to Depth.VISIBLE)

        subscriptions.update(added = emptySet(), removed = setOf("room-a"))
        subscriptions.snapshot().toSortedMap() shouldBeEqualTo sortedMapOf("room-a" to Depth.OPEN, "room-b" to Depth.VISIBLE)

        subscriptions.release("room-a")
        subscriptions.snapshot().toSortedMap() shouldBeEqualTo sortedMapOf("room-b" to Depth.VISIBLE)
    }
}
