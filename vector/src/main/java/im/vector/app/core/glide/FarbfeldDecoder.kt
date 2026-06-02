/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.Bitmap
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapResource
import java.io.InputStream
import java.nio.ByteBuffer

internal val FARBFELD_MAGIC_BYTES = "farbfeld".toByteArray(Charsets.US_ASCII)

internal fun decodeFarbfeldToBitmap(bytes: ByteArray): Bitmap? {
    if (bytes.size < 16) return null
    for (i in FARBFELD_MAGIC_BYTES.indices) {
        if (bytes[i] != FARBFELD_MAGIC_BYTES[i]) return null
    }
    val w = ((bytes[8].toInt() and 0xff) shl 24) or
            ((bytes[9].toInt() and 0xff) shl 16) or
            ((bytes[10].toInt() and 0xff) shl 8) or
            (bytes[11].toInt() and 0xff)
    val h = ((bytes[12].toInt() and 0xff) shl 24) or
            ((bytes[13].toInt() and 0xff) shl 16) or
            ((bytes[14].toInt() and 0xff) shl 8) or
            (bytes[15].toInt() and 0xff)
    if (w <= 0 || h <= 0) return null
    if (bytes.size.toLong() < 16L + w.toLong() * h.toLong() * 8L) return null
    val pixels = IntArray(w * h)
    var off = 16
    var i = 0
    val total = w * h
    while (i < total) {
        val r = bytes[off].toInt() and 0xff
        val g = bytes[off + 2].toInt() and 0xff
        val b = bytes[off + 4].toInt() and 0xff
        val a = bytes[off + 6].toInt() and 0xff
        pixels[i++] = (a shl 24) or (r shl 16) or (g shl 8) or b
        off += 8
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}

internal class FarbfeldDecoder(private val bitmapPool: BitmapPool) : ResourceDecoder<InputStream, Bitmap> {

    override fun handles(source: InputStream, options: Options): Boolean {
        if (!source.markSupported()) return false
        source.mark(FARBFELD_MAGIC_BYTES.size)
        return try {
            val buf = ByteArray(FARBFELD_MAGIC_BYTES.size)
            var read = 0
            while (read < buf.size) {
                val n = source.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            read == FARBFELD_MAGIC_BYTES.size && buf.contentEquals(FARBFELD_MAGIC_BYTES)
        } finally {
            source.reset()
        }
    }

    override fun decode(source: InputStream, width: Int, height: Int, options: Options): Resource<Bitmap>? {
        return decodeFarbfeldToBitmap(source.readBytes())?.let { BitmapResource.obtain(it, bitmapPool) }
    }
}

internal class FarbfeldByteBufferDecoder(private val bitmapPool: BitmapPool) : ResourceDecoder<ByteBuffer, Bitmap> {

    override fun handles(source: ByteBuffer, options: Options): Boolean {
        if (source.remaining() < FARBFELD_MAGIC_BYTES.size) return false
        val pos = source.position()
        for (i in FARBFELD_MAGIC_BYTES.indices) {
            if (source.get(pos + i) != FARBFELD_MAGIC_BYTES[i]) return false
        }
        return true
    }

    override fun decode(source: ByteBuffer, width: Int, height: Int, options: Options): Resource<Bitmap>? {
        val bytes = ByteArray(source.remaining())
        source.duplicate().get(bytes)
        return decodeFarbfeldToBitmap(bytes)?.let { BitmapResource.obtain(it, bitmapPool) }
    }
}
