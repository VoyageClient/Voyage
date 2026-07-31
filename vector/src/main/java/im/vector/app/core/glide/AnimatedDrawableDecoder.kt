/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.drawable.Drawable
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.resource.gif.GifOptions
import com.github.penfeizhou.animation.apng.APNGDrawable
import com.github.penfeizhou.animation.apng.decode.APNGParser
import com.github.penfeizhou.animation.io.ByteBufferReader
import com.github.penfeizhou.animation.loader.ByteBufferLoader
import java.io.InputStream
import java.nio.ByteBuffer

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
        return object : Resource<Drawable> {
            override fun getResourceClass(): Class<Drawable> = Drawable::class.java
            override fun get(): Drawable = APNGDrawable(loader).apply { setAutoPlay(autoPlay) }
            override fun getSize(): Int = buffer.capacity()
            override fun recycle() = Unit
        }
    }
}

internal class AnimatedStreamDrawableDecoder(
        private val delegate: AnimatedDrawableDecoder
) : ResourceDecoder<InputStream, Drawable> {

    override fun handles(source: InputStream, options: Options): Boolean {
        if (!source.markSupported()) return false
        source.mark(SNIFF_BYTES)
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
