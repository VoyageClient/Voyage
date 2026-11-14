/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.session.room.send.LocalEchoIdentifiers
import org.matrix.android.sdk.internal.session.room.send.LocalEchoRepository
import org.matrix.android.sdk.internal.util.TemporaryFileCreator
import org.matrix.android.sdk.internal.worker.BackgroundTaskContext
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PreparedMediaEchoTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val original: Content = mapOf("url" to "content://example.org/original", "info" to mapOf("w" to 1920, "h" to 1080), "body" to "caption")
    private val prepared: Content = original + ("url" to "file:///cache/compressed.mp4") + ("info" to mapOf("w" to 320, "h" to 176))
    private val uploaded: Content = prepared + ("url" to "mxc://example.org/video")

    @Test
    fun `a failed upload restores the original source and dimensions`() {
        assertEquals(original, restorePreparedMediaEcho(prepared, original, prepared, null))
    }

    @Test
    fun `successful uploads retain their remote media`() {
        assertEquals(uploaded, restorePreparedMediaEcho(uploaded, original, prepared, null))
    }

    @Test
    fun `restoring an upload preserves a changed caption`() {
        val changed = prepared + ("body" to "another caption")
        assertEquals(original + ("body" to "another caption"), restorePreparedMediaEcho(changed, original, prepared, null))
    }

    @Test
    fun `restoring one gallery item leaves siblings uploaded or preparing`() {
        val before = gallery(original, original)
        val local = gallery(prepared, original)
        val current = gallery(prepared, uploaded)
        assertEquals(gallery(original, uploaded), restorePreparedMediaEcho(current, before, local, 0))
    }

    @Test
    fun `restoring an edited upload updates both copies of the media`() {
        val before = original + ("m.new_content" to original)
        val local = prepared + ("m.new_content" to prepared)
        assertEquals(before, restorePreparedMediaEcho(local, before, local, null))
    }

    @Test
    fun `a video points at its compressed file and measured dimensions before upload then restores on failure`() {
        assertPreparedBeforeUpload(ContentAttachmentData.Type.VIDEO)
    }

    @Test
    fun `a photo points at its compressed file and measured dimensions before upload then restores on failure`() {
        assertPreparedBeforeUpload(ContentAttachmentData.Type.IMAGE)
    }

    private fun assertPreparedBeforeUpload(type: ContentAttachmentData.Type) = runBlocking {
        val video = type == ContentAttachmentData.Type.VIDEO
        val compressed = temporaryFolder.newFile().apply { writeBytes(ByteArray(8)) }
        val sourceUrl = "content://example.org/original"
        val echo = EventEntity(eventId = "\$local.upload", roomId = "!room:example.org", type = EventType.MESSAGE).apply {
            content = ContentMapper.map(mapOf(
                    "msgtype" to if (video) "m.video" else "m.image", "body" to if (video) "clip.mp4" else "photo.jpg", "url" to sourceUrl,
                    "info" to mapOf("w" to 1920, "h" to 1080, "size" to 1000, "mimetype" to if (video) "video/mp4" else "image/jpeg"),
            ))
        }
        val before = ContentMapper.map(echo.content, castJsonNumbers = true)
        val repository = mockk<LocalEchoRepository>(relaxed = true)
        coEvery { repository.updateEcho(echo.eventId, any()) } answers { secondArg<(EventEntity) -> Unit>()(echo) }
        val media = mockk<AttachmentMediaProcessor>(relaxed = true)
        coEvery { media.compressVideo(any(), any(), any(), any(), any()) } returns VideoCompressionResult.Success(compressed)
        coEvery { media.compressImage(any(), any(), any(), any(), any()) } returns CompressedImage(compressed, "image/jpeg")
        every { media.readVideoSize(compressed) } returns (320 to 176)
        every { media.readImageSize(compressed) } returns (320 to 176)
        every { media.openSource(any()) } answers { ByteArray(8).inputStream() }
        val uploader = mockk<FileUploader>(relaxed = true)
        var observed: Content? = null
        var fileAvailable = false
        every { uploader.checkUploadSize(compressed) } answers {
            observed = ContentMapper.map(echo.content, castJsonNumbers = true)
            fileAvailable = compressed.exists()
            throw IOException("Synthetic upload failure")
        }
        val task = UploadContentTaskBody(
                fileUploader = uploader, contentUploadStateTracker = mockk(relaxed = true), fileService = mockk(relaxed = true),
                cancelSendTracker = mockk(relaxed = true), media = media, imageExitTagRemover = mockk(relaxed = true),
                lightweightSettingsStorage = mockk(relaxed = true), thumbnailExtractor = mockk(relaxed = true), localEchoRepository = repository,
                temporaryFileCreator = object : TemporaryFileCreator { override suspend fun create(): File = temporaryFolder.newFile() },
                clock = mockk(relaxed = true), backgroundTaskScheduler = mockk(relaxed = true), pendingMediaUploadRegistry = mockk(relaxed = true),
                failedMediaUploadRecovery = mockk(relaxed = true),
        )
        task.execute(
                UploadContentWorkerParams(
                        sessionId = "session", localEchoIds = listOf(LocalEchoIdentifiers(echo.roomId, echo.eventId)),
                        attachment = ContentAttachmentData(queryUri = sourceUrl, mimeType = if (video) "video/mp4" else "image/jpeg", type = type),
                        isEncrypted = false, compressBeforeSending = true,
                ),
                mockk<BackgroundTaskContext>(relaxed = true),
        )

        val prepared = checkNotNull(observed)
        assertEquals(compressed.toURI().path, android.net.Uri.parse(prepared["url"] as String).path)
        val info = prepared["info"] as Map<*, *>
        assertEquals(320L, info["w"])
        assertEquals(176L, info["h"])
        if (video) {
            assertEquals(prepared["url"], info["thumbnail_url"])
            assertEquals(320L, (info["thumbnail_info"] as Map<*, *>)["w"])
            assertEquals(176L, (info["thumbnail_info"] as Map<*, *>)["h"])
        }
        assertTrue(fileAvailable)
        assertEquals(before, ContentMapper.map(echo.content, castJsonNumbers = true))
        assertFalse(compressed.exists())
    }

    private fun gallery(vararg items: Content): Content = mapOf("msgtype" to "m.gallery", "itemtypes" to items.toList())
}
