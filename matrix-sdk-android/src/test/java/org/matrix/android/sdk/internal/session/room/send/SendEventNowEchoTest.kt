/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.internal.crypto.tasks.SendEventTask

class SendEventNowEchoTest {

    private val repository = mockk<LocalEchoRepository>(relaxed = true)
    private val factory = mockk<LocalEchoEventFactory>(relaxed = true).also {
        every { it.createEvent("room", EventType.MESSAGE, any()) } returns Event(type = EventType.MESSAGE, roomId = "room", senderId = "me")
    }
    private val sendEventTask = mockk<SendEventTask>()
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
            sendEventTask = sendEventTask,
            failedMediaUploadRecovery = mockk(relaxed = true),
            contentUploadStateTracker = mockk(relaxed = true),
    )

    /** Sync hands a run of these back several at a time, so each shows as soon as its own send is done. */
    @Test
    fun `a sent event appears straight away, as sent, under its transaction id`() = runBlocking {
        coEvery { sendEventTask.execute(any()) } returns "\$remote"

        service.sendEventNow(EventType.MESSAGE, mapOf("body" to "hi"), "\$local.txn") shouldBeEqualTo "\$remote"

        coVerifyOrder {
            sendEventTask.execute(any())
            repository.createLocalEcho(match { it.eventId == "\$local.txn" && it.unsignedData?.transactionId == "\$local.txn" }, SendState.SENT)
        }
    }

    @Test
    fun `nothing appears when the send fails`() {
        coEvery { sendEventTask.execute(any()) } throws IllegalStateException("offline")

        runCatching { runBlocking { service.sendEventNow(EventType.MESSAGE, mapOf("body" to "hi"), "\$local.txn") } }

        verify(exactly = 0) { repository.createLocalEcho(any(), any()) }
    }
}
