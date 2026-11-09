/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.drawable.Drawable
import com.bumptech.glide.load.EncodeStrategy
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.ResourceEncoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.resource.gif.GifOptions
import com.bumptech.glide.util.ByteBufferUtil
import com.github.penfeizhou.animation.apng.APNGDrawable
import com.github.penfeizhou.animation.apng.decode.APNGDecoder
import com.github.penfeizhou.animation.apng.decode.APNGParser
import com.github.penfeizhou.animation.decode.FrameSeqDecoder
import com.github.penfeizhou.animation.io.ByteBufferReader
import com.github.penfeizhou.animation.loader.ByteBufferLoader
import com.github.penfeizhou.animation.loader.Loader
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes APNG straight into penfeizhou's FrameAnimationDrawable.
 *
 * Glide's bundled AnimatedImageDecoder misses some APNG headers and falls back to a single still
 * frame; penfeizhou's parser reads the acTL chunk directly.
 *
 * WebP (static + animated, all variants) is owned by zjupure's native libwebp decoder, not this one:
 * penfeizhou decodes every WebP frame through BitmapFactory, which can't read VP8L/VP8X below API 18.
 *
 * GIF is deliberately left to Glide's built-in (pure-Java) StandardGifDecoder: penfeizhou's GIF
 * module decodes LZW in a native lib that fails to load on KitKat (NoClassDefFoundError on GifFrame).
 *
 * The Resource builds a fresh Drawable on every get(): a single FrameAnimationDrawable shares one
 * decoder, so if Glide handed the same instance to several targets they would all show the same
 * frame and fight over a single callback. A per-bind drawable gives each view its own animation
 * state. The ByteBufferLoader is shared, so the source bytes are decoded once.
 *
 * The factory does not start the decoder eagerly: Glide's transformation pipeline calls get()
 * several times per cache miss and only one result reaches a target, so eager starts would leave
 * orphaned decoders running. Real consumers kick the decoder off instead — ImageView via Glide's
 * Animatable.start(), markwon via EventHtmlRenderer's RequestListener.
 */
internal class AnimatedDrawableDecoder : ResourceDecoder<ByteBuffer, Drawable> {

    override fun handles(source: ByteBuffer, options: Options): Boolean {
        return runCatching { APNGParser.isAPNG(ByteBufferReader(source.asReadOnlyBuffer())) }.getOrDefault(false)
    }

    override fun decode(source: ByteBuffer, width: Int, height: Int, options: Options): Resource<Drawable>? {
        val buffer = source.asReadOnlyBuffer()
        val loader = object : ByteBufferLoader() {
            override fun getByteBuffer(): ByteBuffer = buffer.asReadOnlyBuffer()
        }
        if (!runCatching { APNGParser.isAPNG(ByteBufferReader(buffer.asReadOnlyBuffer())) }.getOrDefault(false)) {
            return null
        }
        // penfeizhou's autoPlay restarts the animation on every setVisible(true), outliving a target's stop().
        val autoPlay = options.get(GifOptions.DISABLE_ANIMATION) != true
        val viewerSize = pngSize(buffer).takeIf { ViewerDownsample.appliesTo(options) }
        val sampleSize = viewerSize?.let { ViewerDownsample.sampleSizeFor(it.first, it.second) } ?: 1
        return object : Resource<Drawable> {
            // The real class, since Glide picks the cache encoder by it (ApngSourceEncoder).
            @Suppress("UNCHECKED_CAST")
            override fun getResourceClass(): Class<Drawable> = SourceApngDrawable::class.java as Class<Drawable>
            override fun get(): Drawable = SourceApngDrawable(SampledLoader(loader, sampleSize), buffer).apply { setAutoPlay(autoPlay) }
            override fun getSize(): Int = buffer.capacity()
            override fun recycle() = Unit
        }
    }
}

private fun pngSize(buffer: ByteBuffer): Pair<Int, Int>? {
    val view = buffer.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN)
    if (view.remaining() < PNG_IHDR_SIZE_END) return null
    val base = view.position()
    return view.getInt(base + PNG_IHDR_WIDTH_OFFSET) to view.getInt(base + PNG_IHDR_WIDTH_OFFSET + 4)
}

private const val PNG_IHDR_WIDTH_OFFSET = 16
private const val PNG_IHDR_SIZE_END = 24

// createFrameSeqDecoder() runs inside the super constructor, before any field of ours is set.
private class SampledLoader(private val delegate: Loader, val sampleSize: Int) : Loader by delegate

/** Keeps the APNG's bytes, which are what [ApngSourceEncoder] stores as its resource-cache entry. */
internal class SourceApngDrawable(loader: Loader, val source: ByteBuffer) : APNGDrawable(loader) {

    override fun createFrameSeqDecoder(loader: Loader, listener: FrameSeqDecoder.RenderListener): APNGDecoder =
            SampledApngDecoder(loader, listener, (loader as? SampledLoader)?.sampleSize ?: 1)
}

// penfeizhou sizes the frame buffer by the sample size at first parse, so it must be set up front.
private class SampledApngDecoder(loader: Loader, listener: FrameSeqDecoder.RenderListener, sample: Int) : APNGDecoder(loader, listener) {
    init {
        sampleSize = sample
    }
}

/**
 * Caches a decoded APNG as its original bytes, which [AnimatedDrawableDecoder] reads back on a cache hit.
 * The drawable has no downsampled form to store; without any encoder Glide fails the whole load instead.
 */
internal class ApngSourceEncoder : ResourceEncoder<APNGDrawable> {

    override fun getEncodeStrategy(options: Options): EncodeStrategy = EncodeStrategy.SOURCE

    override fun encode(data: Resource<APNGDrawable>, file: File, options: Options): Boolean {
        val drawable = data.get() as? SourceApngDrawable ?: return false
        return try {
            ByteBufferUtil.toFile(drawable.source.asReadOnlyBuffer(), file)
            true
        } catch (e: IOException) {
            Timber.w(e, "Failed to cache an APNG")
            false
        }
    }
}

internal class AnimatedStreamDrawableDecoder(
        private val delegate: AnimatedDrawableDecoder
) : ResourceDecoder<InputStream, Drawable> {

    override fun handles(source: InputStream, options: Options): Boolean {
        if (!source.markSupported()) return false
        source.mark(SNIFF_MARK_BYTES)
        return try {
            val buf = ByteArray(SNIFF_BYTES)
            var read = 0
            while (read < buf.size) {
                val n = source.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            read > 0 && delegate.handles(ByteBuffer.wrap(buf, 0, read), options)
        } finally {
            source.reset()
        }
    }

    override fun decode(source: InputStream, width: Int, height: Int, options: Options): Resource<Drawable>? {
        return delegate.decode(ByteBuffer.wrap(source.readBytes()), width, height, options)
    }

    companion object {
        private const val SNIFF_BYTES = 8 * 1024
    }
}
