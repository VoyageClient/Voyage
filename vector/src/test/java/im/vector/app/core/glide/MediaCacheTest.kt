/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.cache.DiskCache
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.file.FileService
import java.io.File

class MediaCacheTest {

    private val glide = mockk<Glide>(relaxed = true)
    private val fileService = mockk<FileService>(relaxed = true)
    private val session = mockk<Session> {
        every { fileService() } returns fileService
    }

    @get:Rule
    val cacheDir = TemporaryFolder()

    @get:Rule
    val filesDir = TemporaryFolder()

    private val context = mockk<Context> {
        // Answered lazily: the rules only create the folders once the test starts.
        every { cacheDir } answers { this@MediaCacheTest.cacheDir.root }
        every { filesDir } answers { this@MediaCacheTest.filesDir.root }
    }

    private val mediaCache = MediaCache(context)

    init {
        mockkStatic(Glide::class)
        every { Glide.get(any()) } returns glide
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `clearing the media cache drops every cached thumbnail and every downloaded file`() = runTest {
        val edited = MediaCache.editedMediaDirectory(context).also { it.mkdirs() }
        edited.resolve("an-edited-attachment").writeBytes(ByteArray(size = 128))

        mediaCache.clear(listOf(session))

        verify { glide.clearMemory() }
        verify { glide.clearDiskCache() }
        verify { fileService.clearCache() }
        // Exports are full-size copies that nothing else reclaims.
        edited.exists() shouldBeEqualTo false
    }

    @Test
    fun `clearing media for multiple accounts clears each file service`() = runTest {
        val otherFileService = mockk<FileService>(relaxed = true)
        val otherSession = mockk<Session> {
            every { fileService() } returns otherFileService
        }

        mediaCache.clear(listOf(session, otherSession))

        verify(exactly = 1) { glide.clearMemory() }
        verify(exactly = 1) { glide.clearDiskCache() }
        verify(exactly = 1) { fileService.clearCache() }
        verify(exactly = 1) { otherFileService.clearCache() }
    }

    @Test
    fun `media size includes downloads from all accounts and shared cache once`() = runTest {
        val thumbnails = File(cacheDir.root, DiskCache.Factory.DEFAULT_DISK_CACHE_DIR).also { it.mkdirs() }
        val firstDownloads = File(cacheDir.root, "downloads/first/F").also { it.mkdirs() }
        val secondDownloads = File(cacheDir.root, "downloads/second/F").also { it.mkdirs() }
        val emptySize = mediaCache.sizeForAccounts(listOf("first", "second"))

        thumbnails.resolve("thumbnail").writeBytes(ByteArray(64))
        firstDownloads.resolve("first-file").writeBytes(ByteArray(128))
        secondDownloads.resolve("second-file").writeBytes(ByteArray(256))

        mediaCache.sizeForAccounts(listOf("first", "second")) shouldBeEqualTo emptySize + 448
    }

    @Test
    fun `an edited attachment counts towards the reported size`() = runTest {
        val edited = MediaCache.editedMediaDirectory(context).also { it.mkdirs() }
        val sizeWithoutEdits = mediaCache.sizeForAccounts(emptyList())

        edited.resolve("an-edited-attachment").writeBytes(ByteArray(size = 256))

        mediaCache.sizeForAccounts(emptyList()) shouldBeEqualTo sizeWithoutEdits + 256
    }

    @Test
    fun `the memory cache is cleared on the caller's thread and the disk cache off it`() = runTest {
        val threads = mutableMapOf<String, Thread>()
        every { glide.clearMemory() } answers { threads["memory"] = Thread.currentThread() }
        every { glide.clearDiskCache() } answers { threads["disk"] = Thread.currentThread() }

        mediaCache.clearThumbnails()

        // Glide throws if the memory cache is cleared off the main thread, or the disk cache on it.
        threads["memory"] shouldBeEqualTo Thread.currentThread()
        (threads["disk"] == Thread.currentThread()) shouldBeEqualTo false
    }

    @Test
    fun `clearing thumbnails leaves downloaded files alone`() = runTest {
        mediaCache.clearThumbnails()

        verify { glide.clearMemory() }
        verify { glide.clearDiskCache() }
        verify(exactly = 0) { fileService.clearCache() }
    }

    @Test
    fun `an untouched cache has no size`() = runTest {
        mediaCache.sizeForAccounts(emptyList()) shouldBeEqualTo 0L
    }
}
