/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.internal.platform.BackgroundTaskRequest
import org.matrix.android.sdk.internal.platform.BackgroundTaskScheduler
import org.matrix.android.sdk.internal.session.content.DefaultContentUploadStateTracker
import org.matrix.android.sdk.internal.session.content.UploadContentWorkerParams
import org.matrix.android.sdk.internal.session.room.send.queue.EventSenderProcessor

class GalleryResendTest {

    private val scheduler = mockk<BackgroundTaskScheduler>(relaxed = true)
    private val repository = mockk<LocalEchoRepository>(relaxed = true)
    private val tracker = mockk<DefaultContentUploadStateTracker>(relaxed = true)
    private val eventSenderProcessor = mockk<EventSenderProcessor>(relaxed = true)

    private val service = DefaultSendService(
            roomId = ROOM,
            backgroundTaskScheduler = scheduler,
            sessionId = "session",
            userId = "me",
            localEchoEventFactory = mockk(relaxed = true),
            cryptoStore = mockk(relaxed = true),
            taskExecutor = mockk(relaxed = true),
            localEchoRepository = repository,
            sendOutcomeTracker = mockk(relaxed = true),
            eventSenderProcessor = eventSenderProcessor,
            cancelSendTracker = mockk(relaxed = true),
            pendingMediaUploadRegistry = mockk(relaxed = true),
            linkPreviewPrefetcher = mockk(relaxed = true),
            mediaSendOrder = mockk(relaxed = true),
            sendEventTask = mockk(relaxed = true),
            failedMediaUploadRecovery = mockk(relaxed = true),
            contentUploadStateTracker = tracker,
    )

    private fun failedGallery(vararg urls: String): TimelineEvent {
        val items = urls.map { url ->
            mapOf(
                    "itemtype" to "m.image",
                    "body" to "image.png",
                    "url" to url,
                    "info" to mapOf("mimetype" to "image/png", "size" to 100, "w" to 10, "h" to 10),
            )
        }
        val root = Event(
                eventId = ECHO,
                roomId = ROOM,
                type = "m.room.message",
                content = mapOf("msgtype" to "dm.filament.gallery", "body" to "caption", "itemtypes" to items),
        ).apply { sendState = SendState.UNDELIVERED }
        val sender = SenderInfo(userId = "me", displayName = null, isUniqueDisplayName = true, avatarUrl = null)
        return TimelineEvent(root = root, localId = 1, eventId = ECHO, senderInfo = sender)
    }

    @Test
    fun `only the items that never reached the server are uploaded again`() {
        val uploads = slot<List<BackgroundTaskRequest<*>>>()
        every { scheduler.enqueueUniqueParallelChain(any(), any(), capture(uploads), any()) } returns mockk(relaxed = true)

        service.resendMediaMessage(failedGallery("mxc://server/kept", "file:///kept/refused.png", "mxc://server/also-kept"))

        val params = uploads.captured.map { it.params as UploadContentWorkerParams }
        assertEquals(listOf(1), params.map { it.galleryItemIndex })
        assertEquals("file:///kept/refused.png", params.single().attachment.queryUri)
        verify { tracker.setGalleryItemSettled(ECHO, 0, any()) }
        verify { tracker.setGalleryItemSettled(ECHO, 2, any()) }
        verify(exactly = 0) { tracker.setGalleryItemSettled(ECHO, 1, any()) }
        verify { repository.updateSendState(ECHO, ROOM, SendState.UNSENT, any()) }
    }

    @Test
    fun `a gallery whose items all landed only resends the event`() {
        service.resendMediaMessage(failedGallery("mxc://server/a", "mxc://server/b"))

        verify(exactly = 0) { scheduler.enqueueUniqueParallelChain(any(), any(), any(), any()) }
        verify { eventSenderProcessor.postEvent(match<Event> { it.eventId == ECHO }) }
    }

    private companion object {
        const val ROOM = "!room:server"
        const val ECHO = "\$local.gallery"
    }
}
