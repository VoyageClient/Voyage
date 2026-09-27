/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.animatedimage

import android.graphics.Bitmap
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer

object GifFrameReader {

    fun readFrames(file: File): List<AnimatedFrame>? = collectFrames { visitFrames(file, it) }

    fun visitFrames(file: File, onFrame: (AnimatedFrame, Int, Int) -> Unit): Boolean {
        val data = try {
            file.readBytes()
        } catch (error: Throwable) {
            Timber.w(error, "GIF: cannot read source")
            return false
        }
        val header = try {
            GifHeaderParser().setData(data).parseHeader()
        } catch (error: Throwable) {
            Timber.w(error, "GIF: cannot parse header")
            return false
        }
        val decoder = StandardGifDecoder(SimpleBitmapProvider, header, ByteBuffer.wrap(data), 1)
        try {
            if (decoder.frameCount <= 0) return false
            for (index in 0 until decoder.frameCount) {
                decoder.advance()
                val bitmap = decoder.nextFrame ?: return false
                try {
                    onFrame(AnimatedFrame(bitmap, decoder.getDelay(index).coerceAtLeast(MIN_FRAME_DELAY_MS)), index, decoder.frameCount)
                } finally {
                    bitmap.recycle()
                }
            }
            return true
        } finally {
            decoder.clear()
        }
    }

    private object SimpleBitmapProvider : GifDecoder.BitmapProvider {
        override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap =
                Bitmap.createBitmap(width, height, config)

        override fun release(bitmap: Bitmap) { bitmap.recycle() }

        override fun obtainByteArray(size: Int): ByteArray = ByteArray(size)
        override fun release(bytes: ByteArray) { /* no-op */ }
        override fun obtainIntArray(size: Int): IntArray = IntArray(size)
        override fun release(array: IntArray) { /* no-op */ }
    }
}
