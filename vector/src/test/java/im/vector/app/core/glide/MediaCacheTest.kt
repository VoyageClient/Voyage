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

    private val context = mockk<Context> {
        // Answered lazily: the rule only creates the folder once the test starts.
        every { cacheDir } answers { this@MediaCacheTest.cacheDir.root }
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
        mediaCache.clear(session)

        verify { glide.clearMemory() }
        verify { glide.clearDiskCache() }
        verify { fileService.clearCache() }
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
    fun `a cached thumbnail counts towards the reported size`() = runTest {
        every { fileService.getCacheSize() } returns 1024L
        val thumbnails = File(cacheDir.root, DiskCache.Factory.DEFAULT_DISK_CACHE_DIR).also { it.mkdirs() }
        val sizeWithoutThumbnails = mediaCache.size(session)

        thumbnails.resolve("a-thumbnail").writeBytes(ByteArray(size = 512))

        mediaCache.size(session) shouldBeEqualTo sizeWithoutThumbnails + 512
    }

    @Test
    fun `a downloaded file counts towards the reported size`() = runTest {
        every { fileService.getCacheSize() } returns 1024L
        val sizeWithOneFile = mediaCache.size(session)

        every { fileService.getCacheSize() } returns 2048L

        mediaCache.size(session) shouldBeEqualTo sizeWithOneFile + 1024
    }

    @Test
    fun `an untouched cache has no size`() = runTest {
        every { fileService.getCacheSize() } returns 0L

        mediaCache.size(session) shouldBeEqualTo 0L
    }
}
