/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType

class PollVoteWithdrawalTest {

    private val repository = mockk<LocalEchoRepository>(relaxed = true)
    private val factory = mockk<LocalEchoEventFactory>(relaxed = true)
    private val service = DefaultSendService(
            roomId = "room",
            backgroundTaskScheduler = mockk(relaxed = true),
            sessionId = "session",
            userId = "me",
            localEchoEventFactory = factory,
            cryptoStore = mockk(relaxed = true),
            taskExecutor = mockk(relaxed = true),
            localEchoRepository = repository,
            sendOutcomeTracker = mockk(relaxed = true),
            eventSenderProcessor = mockk(relaxed = true),
            cancelSendTracker = mockk(relaxed = true),
            pendingMediaUploadRegistry = mockk(relaxed = true),
            linkPreviewPrefetcher = mockk(relaxed = true),
            mediaSendOrder = mockk(relaxed = true),
    )

    @Test
    fun `withdrawal redacts earlier votes before latest and resolves a sent local echo`() {
        val older = Event(eventId = "older", roomId = "room", type = EventType.POLL_RESPONSE.unstable)
        val latest = Event(eventId = "local", roomId = "room", type = EventType.POLL_RESPONSE.stable)
        every { repository.getPollVoteEvents("room", "poll", "me") } returns listOf(older, latest)
        every { repository.getRemoteEchoId("older") } returns null
        every { repository.getRemoteEchoId("local") } returns "remote"
        every { factory.createRedactEvent("room", any(), null, null, null) } answers {
            Event(eventId = "redaction", roomId = "room", redacts = secondArg())
        }
        service.withdrawPollVote("poll")
        verifyOrder {
            factory.createRedactEvent("room", "older", null, null, null)
            factory.createRedactEvent("room", "remote", null, null, null)
        }
    }
}
