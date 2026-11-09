/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.content.Context
import android.os.Build
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.integration.webp.decoder.ByteBufferWebpDecoder
import com.bumptech.glide.integration.webp.decoder.StreamWebpDecoder
import com.bumptech.glide.integration.webp.decoder.WebpDrawable
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.resource.gif.ByteBufferGifDecoder
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.load.resource.gif.StreamGifDecoder
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Hands third-party GIF and WebP decoders a box within [ViewerDownsample]'s budget; they'd otherwise decode SIZE_ORIGINAL in full. */
internal object ViewerBoundedDecoders {

    fun register(context: Context, glide: Glide, registry: Registry) {
        val webp = ViewerBoundedDecoder(ByteBufferWebpDecoder(context, glide.arrayPool, glide.bitmapPool), ::webpCanvasSize)
        registry.prepend(ByteBuffer::class.java, WebpDrawable::class.java, webp)
        registry.prepend(InputStream::class.java, WebpDrawable::class.java, ViewerOnlyStreamDecoder(StreamWebpDecoder(webp, glide.arrayPool)))
        // From API 28 GIFs go through PlatformGifDecoders, which bounds them itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            val parsers = registry.imageHeaderParsers
            val gif = ViewerBoundedDecoder(ByteBufferGifDecoder(context, parsers, glide.bitmapPool, glide.arrayPool), ::gifScreenSize)
            registry.prepend(ByteBuffer::class.java, GifDrawable::class.java, gif)
            registry.prepend(InputStream::class.java, GifDrawable::class.java, ViewerOnlyStreamDecoder(StreamGifDecoder(parsers, gif, glide.arrayPool)))
        }
    }

    /** Logical screen size, little-endian right after the 6-byte signature. */
    internal fun gifScreenSize(source: ByteBuffer): Pair<Int, Int>? {
        val view = source.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        if (view.remaining() < GIF_SIZE_END) return null
        val base = view.position()
        return view.getShort(base + GIF_WIDTH_OFFSET).toInt().and(0xFFFF) to view.getShort(base + GIF_WIDTH_OFFSET + 2).toInt().and(0xFFFF)
    }

    /** Canvas size from the VP8X chunk, which every animated WebP has: 24-bit little-endian, minus one. */
    internal fun webpCanvasSize(source: ByteBuffer): Pair<Int, Int>? {
        val view = source.asReadOnlyBuffer()
        if (view.remaining() < VP8X_SIZE_END) return null
        val base = view.position()
        val isVp8x = view.get(base + VP8X_FOURCC_OFFSET) == 'V'.code.toByte() && view.get(base + VP8X_FOURCC_OFFSET + 1) == 'P'.code.toByte() &&
                view.get(base + VP8X_FOURCC_OFFSET + 2) == '8'.code.toByte() && view.get(base + VP8X_FOURCC_OFFSET + 3) == 'X'.code.toByte()
        if (!isVp8x) return null
        fun uint24(offset: Int) = (view.get(base + offset).toInt() and 0xFF) or
                ((view.get(base + offset + 1).toInt() and 0xFF) shl 8) or
                ((view.get(base + offset + 2).toInt() and 0xFF) shl 16)
        return uint24(VP8X_WIDTH_OFFSET) + 1 to uint24(VP8X_WIDTH_OFFSET + 3) + 1
    }

    private const val GIF_WIDTH_OFFSET = 6
    private const val GIF_SIZE_END = 10
    private const val VP8X_FOURCC_OFFSET = 12
    private const val VP8X_WIDTH_OFFSET = 24
    private const val VP8X_SIZE_END = 30
}

private class ViewerBoundedDecoder<T : Any>(
        private val delegate: ResourceDecoder<ByteBuffer, T>,
        private val readSize: (ByteBuffer) -> Pair<Int, Int>?,
) : ResourceDecoder<ByteBuffer, T> {

    override fun handles(source: ByteBuffer, options: Options): Boolean =
            ViewerDownsample.appliesTo(options) && delegate.handles(source, options)

    override fun decode(source: ByteBuffer, width: Int, height: Int, options: Options): Resource<T>? {
        val size = readSize(source) ?: return delegate.decode(source, width, height, options)
        val sample = ViewerDownsample.sampleSizeFor(size.first, size.second)
        if (sample == 1) return delegate.decode(source, width, height, options)
        val boxWidth = (size.first / sample).coerceAtLeast(1)
        val boxHeight = (size.second / sample).coerceAtLeast(1)
        return delegate.decode(source, minOf(boxWidth, width.orUnbounded()), minOf(boxHeight, height.orUnbounded()), options)
    }

    // Also covers Target.SIZE_ORIGINAL, which is negative.
    private fun Int.orUnbounded() = if (this <= 0) Int.MAX_VALUE else this
}

// The stream decoders never consult their ByteBuffer decoder's handles(), so the gate is repeated here.
private class ViewerOnlyStreamDecoder<T : Any>(private val delegate: ResourceDecoder<InputStream, T>) : ResourceDecoder<InputStream, T> {

    override fun handles(source: InputStream, options: Options): Boolean =
            ViewerDownsample.appliesTo(options) && delegate.handles(source, options)

    override fun decode(source: InputStream, width: Int, height: Int, options: Options): Resource<T>? =
            delegate.decode(source, width, height, options)
}
