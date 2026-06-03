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
import com.bumptech.glide.load.resource.SimpleResource
import com.github.penfeizhou.animation.apng.APNGDrawable
import com.github.penfeizhou.animation.apng.decode.APNGParser
import com.github.penfeizhou.animation.io.ByteBufferReader
import com.github.penfeizhou.animation.loader.ByteBufferLoader
import com.github.penfeizhou.animation.webp.WebPDrawable
import com.github.penfeizhou.animation.webp.decode.WebPParser
import java.nio.ByteBuffer

/**
 * Decodes animated WebP and APNG straight into penfeizhou's WebPDrawable / APNGDrawable.
 *
 * Glide's bundled AnimatedImageDecoder doesn't detect every animated WebP — its ImageHeaderParser
 * misses the VP8X / ANIM chunk on some sources. penfeizhou's parsers read those chunks directly.
 *
 * setVisible(true, true) starts first-frame decoding before the drawable is handed to any target.
 * ImageView does this for us on attach, but markwon's AsyncDrawable doesn't propagate visibility,
 * so without it inline-emoji drawables stay blank.
 */
internal class AnimatedDrawableDecoder : ResourceDecoder<ByteBuffer, Drawable> {

    override fun handles(source: ByteBuffer, options: Options): Boolean {
        return runCatching { WebPParser.isAWebP(ByteBufferReader(source.asReadOnlyBuffer())) }.getOrDefault(false) ||
                runCatching { APNGParser.isAPNG(ByteBufferReader(source.asReadOnlyBuffer())) }.getOrDefault(false)
    }

    override fun decode(source: ByteBuffer, width: Int, height: Int, options: Options): Resource<Drawable>? {
        val buffer = source.asReadOnlyBuffer()
        val loader = object : ByteBufferLoader() {
            override fun getByteBuffer(): ByteBuffer = buffer.asReadOnlyBuffer()
        }
        val drawable: Drawable = when {
            runCatching { WebPParser.isAWebP(ByteBufferReader(buffer.asReadOnlyBuffer())) }.getOrDefault(false) ->
                WebPDrawable(loader).apply { setAutoPlay(true); setVisible(true, true) }
            runCatching { APNGParser.isAPNG(ByteBufferReader(buffer.asReadOnlyBuffer())) }.getOrDefault(false) ->
                APNGDrawable(loader).apply { setAutoPlay(true); setVisible(true, true) }
            else -> return null
        }
        return SimpleResource(drawable)
    }
}
