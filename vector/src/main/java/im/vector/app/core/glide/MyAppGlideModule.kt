/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.module.AppGlideModule
import im.vector.app.features.media.ImageContentRenderer
import java.io.InputStream
import java.nio.ByteBuffer

@GlideModule
class MyAppGlideModule : AppGlideModule() {

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        builder.setLogLevel(Log.ERROR)
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.append(
                ImageContentRenderer.Data::class.java,
                InputStream::class.java,
                ImageContentRendererDataLoaderFactory(context)
        )
        registry.append(
                AvatarPlaceholder::class.java,
                Drawable::class.java,
                AvatarPlaceholderModelLoaderFactory(context)
        )
        // Prepend so text-based formats (XPM, SVG) are caught before Glide falls through to
        // the default BitmapFactory decoder which would error on non-binary inputs. SVG stays
        // a vector all the way to the ImageView so pinch-zoom keeps it crisp.
        registry.prepend(InputStream::class.java, Bitmap::class.java, XpmDecoder(glide.bitmapPool))
        registry.prepend(InputStream::class.java, Bitmap::class.java, FarbfeldDecoder(glide.bitmapPool))
        registry.prepend(ByteBuffer::class.java, Bitmap::class.java, FarbfeldByteBufferDecoder(glide.bitmapPool))
        registry.prepend(InputStream::class.java, Drawable::class.java, SvgDecoder())
        // Animated WebP / APNG via penfeizhou. Glide 4.16's bundled AnimatedImageDecoder doesn't
        // pick our content up — its ImageHeaderParser misses the VP8X / ANIM chunk on some
        // sources — so without our own decoder the still-Bitmap path wins, the resource gets
        // cached as a Bitmap, and the image freezes on a single frame forever after.
        //
        // Register on the bucketless "legacy_prepend_all" bucket, which Glide's
        // setResourceDecoderBucketPriorityList puts FIRST in priority — ahead of the
        // Animation / Bitmap / BitmapDrawable buckets. This call must come AFTER our other
        // bucketless prepends above so it lands at position 0 of that bucket's entry list and is
        // the first entry iterated when Glide enumerates resource classes for ByteBuffer source
        // data. Otherwise FarbfeldByteBufferDecoder (ByteBuffer→Bitmap, also bucketless) sits
        // earlier in iteration order, Bitmap is added to the resource class list first, and
        // Glide's path enumeration picks the still-Bitmap path before ever trying ours.
        registry.prepend(ByteBuffer::class.java, Drawable::class.java, AnimatedDrawableDecoder())
        // Give Glide a Uri -> ByteBuffer path so attachment-preview loads can hand bytes straight
        // to penfeizhou's ByteBufferAnimationDecoder without us blocking the main thread to read
        // them ourselves. The fetcher runs on Glide's source executor.
        registry.prepend(Uri::class.java, ByteBuffer::class.java, UriByteBufferLoaderFactory(context))
    }
}
