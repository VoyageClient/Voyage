/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.session.room.timeline.TimelineSettings
import org.matrix.android.sdk.internal.session.sync.sliding.SlidingSyncRoomSubscriptions

@OptIn(ExperimentalCoroutinesApi::class)
class SqlTimelineSubscriptionsTest {

    @Test
    fun `background timelines do not subscribe to the room`() = runTest {
        val subscriptions = SlidingSyncRoomSubscriptions()
        val timeline = timeline(subscriptions, TimelineSettings(initialSize = 1, subscribeToRoom = false))
        try {
            timeline.start()
            assertTrue(subscriptions.snapshot().isEmpty())
            timeline.setPaused(true)
            timeline.setPaused(false)
            assertTrue(subscriptions.snapshot().isEmpty())
        } finally {
            timeline.dispose()
        }
    }

    @Test
    fun `a timeline paused before starting subscribes only when resumed`() = runTest {
        val subscriptions = SlidingSyncRoomSubscriptions()
        val timeline = timeline(subscriptions, TimelineSettings(initialSize = 1))
        try {
            timeline.setPaused(true)
            timeline.start()
            assertTrue(subscriptions.snapshot().isEmpty())

            timeline.setPaused(false)
            assertEquals(mapOf("!room:example.org" to SlidingSyncRoomSubscriptions.Depth.OPEN), subscriptions.snapshot())

            timeline.setPaused(true)
            assertTrue(subscriptions.snapshot().isEmpty())
        } finally {
            timeline.dispose()
        }
    }

    private fun timeline(subscriptions: SlidingSyncRoomSubscriptions, settings: TimelineSettings): SqlTimeline {
        val dispatcher = StandardTestDispatcher()
        return SqlTimeline(
                roomId = "!room:example.org",
                initialEventId = null,
                settings = settings,
                coroutineDispatchers = MatrixCoroutineDispatchers(dispatcher, dispatcher, dispatcher, dispatcher, dispatcher),
                stores = mockk(relaxed = true),
                snapshotLoader = mockk(relaxed = true),
                paginationTask = mockk(relaxed = true),
                fetchThreadTimelineTask = mockk(relaxed = true),
                contextOfEventTask = mockk(relaxed = true),
                fetchEventTokenTask = mockk(relaxed = true),
                fetchRoomStartTask = mockk(relaxed = true),
                database = mockk(relaxed = true),
                sessionDispatcher = dispatcher,
                readDispatcher = dispatcher,
                eventDecryptor = mockk(relaxed = true),
                timelineInput = TimelineInput(),
                clock = mockk(relaxed = true),
                redactionSignal = TimelineRedactionSignal(),
                decryptionSignal = TimelineDecryptionSignal(),
                loadRoomMembersTask = mockk(relaxed = true),
                slidingSyncRoomSubscriptions = subscriptions,
        )
    }
}
