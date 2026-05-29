/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.github.penfeizhou.animation.decode.FrameSeqDecoder
import com.github.penfeizhou.animation.glide.ByteBufferAnimationDecoder
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Replacement for penfeizhou's StreamAnimationDecoder. The bundled one runs the WebP / APNG /
 * GIF probes in sequence without resetting the InputStream between them, so as soon as the first
 * probe consumes any bytes the next probe reads from the wrong offset and returns false. APNGs
 * coming through Glide as InputStream (which is what the encrypted timeline and the resolved
 * MXC HTTP loader hand us) therefore silently fall through to the still-bitmap decoder.
 *
 * Here we read once into a ByteBuffer and delegate to [ByteBufferAnimationDecoder], which works
 * off random-access bytes and detects every format the plugin supports.
 */
internal class AnimatedStreamDecoder : ResourceDecoder<InputStream, FrameSeqDecoder<*, *>> {

    private val delegate = ByteBufferAnimationDecoder()

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
            if (read <= 0) false else delegate.handles(ByteBuffer.wrap(buf, 0, read), options)
        } finally {
            source.reset()
        }
    }

    override fun decode(source: InputStream, width: Int, height: Int, options: Options): Resource<FrameSeqDecoder<*, *>>? {
        return delegate.decode(ByteBuffer.wrap(source.readBytes()), width, height, options)
    }

    companion object {
        private const val SNIFF_BYTES = 8 * 1024
    }
}
