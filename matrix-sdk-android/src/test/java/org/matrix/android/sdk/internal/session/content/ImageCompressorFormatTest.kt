/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import im.vector.lib.animatedimage.AnimatedFrame
import im.vector.lib.animatedimage.AnimatedImageFormat
import im.vector.lib.animatedimage.AnimatedImageReader
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.util.MimeTypes
import org.matrix.android.sdk.internal.util.TemporaryFileCreator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageCompressorFormatTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val compressor = ImageCompressor(
            object : TemporaryFileCreator {
                override suspend fun create(): File = temporaryFolder.newFile()
            },
            MatrixCoroutineDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined),
    )

    @Test
    fun `an opaque image is re-encoded as JPEG at the requested size`() = runBlocking {
        val result = compressor.compress(png(Color.RED), 30, 20, exactSize = true)
        assertEquals("image/jpeg", result.mimeType)
        val bitmap = BitmapFactory.decodeFile(result.file.path)
        try {
            assertEquals(30, bitmap.width)
            assertEquals(20, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `a transparent image keeps its alpha as PNG`() = runBlocking {
        val result = compressor.compress(png(Color.TRANSPARENT), 30, 20, exactSize = true)
        assertEquals("image/png", result.mimeType)
        val bitmap = BitmapFactory.decodeFile(result.file.path)
        try {
            assertEquals(0, Color.alpha(bitmap.getPixel(5, 5)))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `an explicit animation resize keeps its requested size even when reencoding makes the file larger`() = runBlocking {
        val gif = gif()

        val result = compressor.compress(gif, 8, 4, exactSize = true)

        assertEquals(MimeTypes.Webp, result.mimeType)
        assertEquals(AnimatedImageFormat.WEBP, result.file.inputStream().use { AnimatedImageFormat.detect(it) })
        val bitmap = BitmapFactory.decodeFile(result.file.path)
        try {
            assertEquals(8, bitmap.width)
            assertEquals(4, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `resizing animation frames does not recycle the readers shared compositing bitmap`() = runBlocking {
        val frame = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888)
        var readerKeptItsBitmap = false
        mockkObject(AnimatedImageReader)
        try {
            every { AnimatedImageReader.visitFrames(any(), any(), any(), any()) } answers {
                val callback = arg<(AnimatedFrame, Int, Int) -> Unit>(3)
                frame.eraseColor(Color.RED)
                callback(AnimatedFrame(frame, 100), 0, 2)
                readerKeptItsBitmap = !frame.isRecycled
                frame.eraseColor(Color.BLUE)
                callback(AnimatedFrame(frame, 200), 1, 2)
                true
            }

            val result = compressor.compress(gif(), 8, 4, exactSize = true)

            assertEquals(MimeTypes.Webp, result.mimeType)
            assertEquals(true, readerKeptItsBitmap)
            assertFalse(frame.isRecycled)
            assertEquals(2, Regex("ANMF").findAll(result.file.readBytes().toString(Charsets.ISO_8859_1)).count())
        } finally {
            unmockkObject(AnimatedImageReader)
            frame.recycle()
        }
    }

    private fun gif() = temporaryFolder.newFile().apply {
        writeBytes(Base64.getDecoder().decode(
                "R0lGODlhEAAIAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAAEAAIAAAIFQABCBxIsKDBgwgTKlzIsKHDhwgDAgAh+QQBFAABACwAAAAAEAAIAIEAAP8AAAAAAAAAAAAIFQABCBxIsKDBgwgTKlzIsKHDhwgDAgA7"
        ))
    }

    private fun png(color: Int): File = temporaryFolder.newFile().apply {
        val bitmap = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
