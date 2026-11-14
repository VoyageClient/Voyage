/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session

import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.session.crypto.attachments.ElementToDecrypt
import org.matrix.android.sdk.internal.session.content.PendingMediaUploadRegistry
import org.matrix.android.sdk.internal.task.TaskExecutor
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DefaultFileServiceUploadCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val dispatchers = MatrixCoroutineDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined,
            Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)
    private val executor = TaskExecutor(dispatchers)

    @After
    fun tearDown() {
        executor.executorScope.cancel()
    }

    @Test
    fun `a pending image decode keeps readable bytes when its upload worker deletes the temporary source`() = runBlocking {
        assertStableHandoff(encrypted = false, uploadFinishesFirst = false)
    }

    @Test
    fun `a pending encrypted image decode keeps its clear bytes after upload cleanup`() = runBlocking {
        assertStableHandoff(encrypted = true, uploadFinishesFirst = false)
    }

    @Test
    fun `an image load started after upload cleanup reads the same cached bytes`() = runBlocking {
        assertStableHandoff(encrypted = false, uploadFinishesFirst = true)
    }

    private suspend fun assertStableHandoff(encrypted: Boolean, uploadFinishesFirst: Boolean) {
        val directory = temporaryFolder.newFolder()
        val pending = PendingMediaUploadRegistry(directory)
        val service = DefaultFileService(
                context = RuntimeEnvironment.getApplication(), sessionCacheDirectory = directory,
                contentUrlResolver = mockk(relaxed = true), okHttpClient = mockk(relaxed = true), coroutineDispatchers = dispatchers,
                clock = mockk(relaxed = true), accessTokenProvider = mockk(relaxed = true), fileUploader = mockk(relaxed = true),
                imageCompressor = mockk(relaxed = true), videoCompressor = mockk(relaxed = true), pendingMediaUploadRegistry = pending,
                taskExecutor = executor,
        )
        val uri = "mxc://example.org/upload"
        val filename = "picture.jpg"
        val mime = "image/jpeg"
        val bytes = byteArrayOf(1, 4, 7, 2, 8)
        val source = temporaryFolder.newFile().apply { writeBytes(bytes) }
        val ciphertext = if (encrypted) temporaryFolder.newFile().apply { writeBytes(byteArrayOf(3, 9, 5)) } else null
        pending.markPending(uri, source, listOfNotNull(ciphertext), setOf("\$local.upload"))
        assertNull(service.getLocalFileFor(uri, filename, mime, encrypted))

        fun finishUpload() {
            service.storeDataFor(uri, filename, mime, source, ciphertext)
            pending.clear(uri)
            assertTrue(source.delete())
            ciphertext?.delete()
        }
        if (uploadFinishesFirst) finishUpload()
        val clear = service.downloadFile(filename, mime, uri, if (encrypted) mockk<ElementToDecrypt>(relaxed = true) else null)
        if (!uploadFinishesFirst) finishUpload()

        assertNotEquals(source, clear)
        assertArrayEquals(bytes, clear.readBytes())
        assertEquals(clear, service.getLocalFileFor(uri, filename, mime, encrypted))
    }
}
