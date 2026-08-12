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
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Animated JPEG XL. Returns null for a still image so it falls through to the Bitmap decoders, which
 * is also what happens on a device below API 21, where none of this is registered at all.
 *
 * One Drawable per Resource, as Glide's own animated decoders do, rather than a fresh one per get()
 * like the APNG decoder: a new instance starts at frame 0 and is not yet running, so every rebind —
 * closing the viewer, scrolling back — would restart the animation instead of leaving it where the
 * cached, still-running drawable had got to.
 */
internal class JxlAnimatedDrawableDecoder : ResourceDecoder<ByteBuffer, Drawable> {

    override fun handles(source: ByteBuffer, options: Options): Boolean {
        val length = minOf(JXL_MAGIC_PEEK_BYTES, source.remaining())
        val head = ByteArray(length)
        source.duplicate().get(head, 0, length)
        return JxlHeader.isJxl(head, length)
    }

    override fun decode(source: ByteBuffer, width: Int, height: Int, options: Options): Resource<Drawable>? {
        val bytes = ByteArray(source.remaining())
        source.duplicate().get(bytes)
        return decodeBytes(bytes, width, height)
    }

    companion object {

        fun decodeBytes(bytes: ByteArray, width: Int, height: Int): Resource<Drawable>? {
            if ((JxlBitmaps.frameCount(bytes) ?: return null) <= 1) return null
            val drawable = JxlBitmaps.animatedDrawable(bytes, width, height) ?: return null
            return object : Resource<Drawable> {
                override fun getResourceClass(): Class<Drawable> = Drawable::class.java
                override fun get(): Drawable = drawable
                override fun getSize(): Int = bytes.size
                override fun recycle() = Unit
            }
        }
    }
}

internal class JxlAnimatedStreamDrawableDecoder : ResourceDecoder<InputStream, Drawable> {

    override fun handles(source: InputStream, options: Options): Boolean {
        if (!source.markSupported()) return false
        source.mark(JXL_SNIFF_MARK_BYTES)
        val head = ByteArray(JXL_MAGIC_PEEK_BYTES)
        val length = try {
            source.read(head)
        } finally {
            source.reset()
        }
        return length > 0 && JxlHeader.isJxl(head, length)
    }

    override fun decode(source: InputStream, width: Int, height: Int, options: Options): Resource<Drawable>? {
        val buffer = ByteArrayOutputStream()
        source.copyTo(buffer)
        return JxlAnimatedDrawableDecoder.decodeBytes(buffer.toByteArray(), width, height)
    }
}
