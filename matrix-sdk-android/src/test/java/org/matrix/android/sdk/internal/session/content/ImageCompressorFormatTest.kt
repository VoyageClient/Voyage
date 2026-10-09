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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.internal.util.TemporaryFileCreator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

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

    private fun png(color: Int): File = temporaryFolder.newFile().apply {
        val bitmap = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
