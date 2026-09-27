/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.state

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.RoomTopicContent
import org.matrix.android.sdk.internal.session.content.ContentUploadResponse
import org.matrix.android.sdk.internal.session.content.FileUploader
import org.matrix.android.sdk.internal.session.content.UploadedMediaCache
import java.io.File

class DefaultStateServiceTest {

    private val fileUploader = mockk<FileUploader>()
    private val uploadedMediaCache = mockk<UploadedMediaCache>(relaxed = true)

    private val sendStateTask = mockk<SendStateTask>()

    private val stateService = DefaultStateService(
            roomId = "!room:example.org",
            userId = "@alice:example.org",
            stateEventDataSource = mockk(),
            sendStateTask = sendStateTask,
            fileUploader = fileUploader,
            uploadedMediaCache = uploadedMediaCache,
            coroutineDispatchers = mockk<MatrixCoroutineDispatchers> { every { io } returns Dispatchers.Unconfined },
    )

    @Test
    fun `uploaded avatar is cached before the prepared file is removed`() = runBlocking {
        val file = File.createTempFile("avatar", ".webp")
        val uploaded = ContentUploadResponse("mxc://example.org/avatar")
        val bytes = byteArrayOf(1, 2, 3)
        file.writeBytes(bytes)
        coEvery { fileUploader.withPreparedUploadFile<ContentUploadResponse>(any(), any()) } coAnswers {
            try {
                secondArg<suspend (File) -> ContentUploadResponse>().invoke(file)
            } finally {
                file.delete()
            }
        }
        coEvery { fileUploader.uploadFile(file, any(), any(), any()) } returns uploaded
        every { uploadedMediaCache.storeDataFor(uploaded.contentUri, null, null, file, null) } answers {
            org.junit.Assert.assertArrayEquals(bytes, file.readBytes())
        }
        coEvery { sendStateTask.executeRetry(any(), any()) } returns "event"

        stateService.updateAvatar("content://avatar", "avatar.webp", false)

        verify(exactly = 1) { uploadedMediaCache.storeDataFor(uploaded.contentUri, null, null, file, null) }
        org.junit.Assert.assertFalse(file.exists())
    }

    private fun sendTopic(topic: String, formattedTopic: String?): RoomTopicContent? {
        val params = slot<SendStateTask.Params>()
        coEvery { sendStateTask.executeRetry(capture(params), any()) } returns "\$event"
        runBlocking { stateService.updateTopic(topic, formattedTopic) }
        return params.captured.body.toModel<RoomTopicContent>()
    }

    @Test
    fun `the legacy field holds a plain-text rendering, not the markdown source`() {
        val content = sendTopic(
                topic = "All about **pizza**",
                formattedTopic = "<p>All about <b>pizza</b></p>"
        )

        assertEquals("All about pizza", content?.topic)
        assertEquals("All about **pizza**", content?.getTopicSource())
        assertEquals("<p>All about <b>pizza</b></p>", content?.getBestFormattedTopic())
    }

    @Test
    fun `a topic without formatting is sent verbatim`() {
        val content = sendTopic(topic = "Kernel talk", formattedTopic = null)

        assertEquals("Kernel talk", content?.topic)
        assertEquals("Kernel talk", content?.getTopicSource())
    }

    @Test
    fun `clearing the topic sends an empty legacy field and no topic block`() {
        val content = sendTopic(topic = "", formattedTopic = null)

        assertEquals("", content?.topic)
        assertNull(content?.getBestFormattedTopic())
    }
}
