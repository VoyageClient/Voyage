/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import im.vector.lib.animatedimage.AnimatedFrame
import im.vector.lib.animatedimage.AnimatedImageFormat
import im.vector.lib.animatedimage.AnimatedImageReader
import im.vector.lib.animatedimage.AnimatedWebpEncoder
import kotlinx.coroutines.CancellationException
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnimatedWebpEncoderTest {

    @Test
    fun `parallel export preserves full resolution and matches sequential frame order and timing`() {
        val frames = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.TRANSPARENT).mapIndexed { index, color ->
            AnimatedFrame(Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }, 20 + index * 30)
        }
        try {
            val sequential = ByteArrayOutputStream()
            val parallel = ByteArrayOutputStream()
            val file = File.createTempFile("animated-stream-", ".webp")

            AnimatedWebpEncoder.encode(frames, 80, sequential) shouldBeEqualTo true
            AnimatedWebpEncoder.encode(frames, 80, parallel, parallel = true) shouldBeEqualTo true

            try {
                AnimatedWebpEncoder.StreamWriter(file, 1024, 768, 80).use { writer ->
                    writer.append(frames.take(2)) shouldBeEqualTo true
                    writer.append(frames.drop(2)) shouldBeEqualTo true
                    writer.finish() shouldBeEqualTo true
                }
                file.readBytes().contentEquals(sequential.toByteArray()) shouldBeEqualTo true
            } finally {
                file.delete()
            }
            val bytes = parallel.toByteArray()
            bytes.contentEquals(sequential.toByteArray()) shouldBeEqualTo true
            (uint24(bytes, 24) + 1) shouldBeEqualTo 1024
            (uint24(bytes, 27) + 1) shouldBeEqualTo 768
        } finally {
            frames.forEach { it.bitmap.recycle() }
        }
    }

    @Test
    fun `streaming GIF decoder releases frames and preserves colors and timing`() {
        val file = gifSource()
        val bitmaps = ArrayList<Bitmap>()
        val colors = ArrayList<Int>()
        val delays = ArrayList<Int>()
        try {
            AnimatedImageReader.visitFrames(file, AnimatedImageFormat.GIF) { frame, index, count ->
                count shouldBeEqualTo 3
                index shouldBeEqualTo bitmaps.size
                colors.add(frame.bitmap.getPixel(0, 0))
                delays.add(frame.durationMs)
                bitmaps.add(frame.bitmap)
            } shouldBeEqualTo true
            colors shouldBeEqualTo listOf(Color.RED, Color.rgb(0, 128, 0), Color.BLUE)
            delays shouldBeEqualTo listOf(20, 50, 100)
            bitmaps.all { it.isRecycled } shouldBeEqualTo true
            val collected = AnimatedImageReader.readFrames(file, AnimatedImageFormat.GIF)!!
            try {
                collected.map { it.bitmap.getPixel(0, 0) } shouldBeEqualTo colors
                collected.none { it.bitmap.isRecycled } shouldBeEqualTo true
            } finally {
                collected.forEach { it.bitmap.recycle() }
            }
        } finally {
            file.delete()
        }
    }

    @Test(expected = CancellationException::class)
    fun `cancellation interrupts frame decoding and releases the borrowed frame`() {
        val file = gifSource()
        var bitmap: Bitmap? = null
        try {
            AnimatedImageReader.visitFrames(file, AnimatedImageFormat.GIF) { frame, _, _ ->
                bitmap = frame.bitmap
                throw CancellationException()
            }
        } finally {
            bitmap?.isRecycled shouldBeEqualTo true
            file.delete()
        }
    }

    private fun gifSource(): File = File.createTempFile("avatar-stream-", ".gif").apply {
        writeBytes(Base64.decode(
                "R0lGODlhIAAYAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQIAgAAACwAAAAAIAAYAAAILwABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFixgzatzIsaPHjyBDihxJsqTJjQEBACH5BAgFAAAALAAAAAAgABgAgQCAAAAAAAAAAAAAAAgvAAEIHEiwoMGDCBMqXMiwocOHECNKnEixosWLGDNq3Mixo8ePIEOKHEmypMmNAQEAIfkECAoAAAAsAAAAACAAGACBAAD/AAAAAAAAAAAACC8AAQgcSLCgwYMIEypcyLChw4cQI0qcSLGixYsYM2rcyLGjx48gQ4ocSbKkyY0BAQA7",
                Base64.DEFAULT
        ))
    }

    private fun uint24(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 255) or
                    ((bytes[offset + 1].toInt() and 255) shl 8) or
                    ((bytes[offset + 2].toInt() and 255) shl 16)
}
