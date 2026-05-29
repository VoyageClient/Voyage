/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import android.graphics.Bitmap
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer

internal object GifFrameReader {

    fun readFrames(file: File): List<AnimatedFrame>? {
        val data = try {
            file.readBytes()
        } catch (t: Throwable) {
            Timber.w(t, "GIF: cannot read source")
            return null
        }
        val header = try {
            GifHeaderParser().setData(data).parseHeader()
        } catch (t: Throwable) {
            Timber.w(t, "GIF: cannot parse header")
            return null
        }
        val decoder = StandardGifDecoder(SimpleBitmapProvider, header, ByteBuffer.wrap(data), 1)
        if (decoder.frameCount <= 0) return null
        val out = ArrayList<AnimatedFrame>(decoder.frameCount)
        for (i in 0 until decoder.frameCount) {
            decoder.advance()
            val frame = decoder.nextFrame ?: break
            // StandardGifDecoder hands back the same Bitmap re-painted each step — copy it so
            // the encoder can safely hold onto multiple frames at once.
            val copy = frame.copy(Bitmap.Config.ARGB_8888, false) ?: continue
            val delay = decoder.getDelay(i).coerceAtLeast(MIN_FRAME_DELAY_MS)
            out.add(AnimatedFrame(copy, delay))
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private object SimpleBitmapProvider : GifDecoder.BitmapProvider {
        override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap =
                Bitmap.createBitmap(width, height, config)

        override fun release(bitmap: Bitmap) { /* the decoder reuses; we don't aggressively recycle */ }

        override fun obtainByteArray(size: Int): ByteArray = ByteArray(size)
        override fun release(bytes: ByteArray) { /* no-op */ }
        override fun obtainIntArray(size: Int): IntArray = IntArray(size)
        override fun release(array: IntArray) { /* no-op */ }
    }

    // Browsers and image viewers clamp very small / zero-delay GIFs to ~100 ms; do the same so
    // the resulting animated WebP plays at a sane speed.
    private const val MIN_FRAME_DELAY_MS = 20
}
