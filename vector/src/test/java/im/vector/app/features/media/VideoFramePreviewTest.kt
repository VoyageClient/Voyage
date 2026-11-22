/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.graphics.Bitmap
import android.graphics.Color
import android.view.TextureView
import im.vector.lib.attachmentviewer.VideoFramePreview
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoFramePreviewTest {

    private val view = mockk<TextureView> {
        every { getBitmap(any<Int>(), any<Int>()) } answers {
            Bitmap.createBitmap(firstArg<Int>(), secondArg<Int>(), Bitmap.Config.ARGB_8888)
        }
    }

    @Test
    fun `portrait frame uses video proportions rather than the fullscreen view proportions`() {
        val frame = VideoFramePreview.capture(view, 440f, 660f, 0, maxDimension = 396)!!

        assertEquals(264, frame.width)
        assertEquals(396, frame.height)
    }

    @Test
    fun `square frame stays square`() {
        val frame = VideoFramePreview.capture(view, 660f, 660f, 0, maxDimension = 396)!!

        assertEquals(396, frame.width)
        assertEquals(396, frame.height)
    }

    @Test
    fun `rotated frame preserves orientation and its pixels`() {
        val pixels = Bitmap.createBitmap(6, 4, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.RED)
            setPixel(5, 3, Color.BLUE)
        }
        every { view.getBitmap(6, 4) } returns pixels

        val frame = VideoFramePreview.capture(view, 6f, 4f, 90)!!

        assertEquals(4, frame.width)
        assertEquals(6, frame.height)
        assertEquals(Color.RED, frame.getPixel(3, 0))
        assertEquals(Color.BLUE, frame.getPixel(0, 5))
    }

    @Test
    fun `small frames are not enlarged`() {
        val frame = VideoFramePreview.capture(view, 44f, 66f, 0)!!

        assertEquals(44, frame.width)
        assertEquals(66, frame.height)
    }

    @Test
    fun `unknown video geometry does not capture a stretched placeholder`() {
        assertNull(VideoFramePreview.capture(view, 0f, 0f, 0))
        verify(exactly = 0) { view.getBitmap(any<Int>(), any<Int>()) }
    }
}
