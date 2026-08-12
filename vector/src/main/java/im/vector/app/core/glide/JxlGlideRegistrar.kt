/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Constructing either decoder loads libjxl, so nothing here may be touched below API 21 — see
 * [org.matrix.android.sdk.api.util.JxlSupport]. Kept out of MyAppGlideModule for that reason.
 */
internal object JxlGlideRegistrar {

    fun register(glide: Glide, registry: Registry) {
        val bitmapPool = glide.bitmapPool
        // Into their own buckets, not the legacy prepend-all one: that bucket outranks Animation, so a
        // Bitmap decoder placed there makes Glide resolve *every* animated image to a still first
        // frame — Downsampler decodes one happily — before the GIF/WebP/APNG decoders are consulted.
        // The animated decoders return null for a still image, so it falls through to the Bitmap ones.
        registry.prepend(Registry.BUCKET_ANIMATION, ByteBuffer::class.java, Drawable::class.java, JxlAnimatedDrawableDecoder())
        registry.prepend(Registry.BUCKET_ANIMATION, InputStream::class.java, Drawable::class.java, JxlAnimatedStreamDrawableDecoder())
        registry.prepend(Registry.BUCKET_BITMAP, ByteBuffer::class.java, Bitmap::class.java, JxlByteBufferBitmapDecoder(bitmapPool))
        registry.prepend(Registry.BUCKET_BITMAP, InputStream::class.java, Bitmap::class.java, JxlStreamBitmapDecoder(bitmapPool))
    }
}
