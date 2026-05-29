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
import com.github.penfeizhou.animation.decode.FrameSeqDecoder
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
        registry.prepend(InputStream::class.java, Drawable::class.java, SvgDecoder())
        // Override penfeizhou's bundled StreamAnimationDecoder: it runs the WebP/APNG/GIF probes
        // back-to-back without resetting the InputStream between them, so any source whose first
        // probe is non-WebP silently mis-detects and falls through to the still-bitmap path.
        // This replacement re-reads from offset 0 for every probe.
        registry.prepend(InputStream::class.java, FrameSeqDecoder::class.java, AnimatedStreamDecoder())
        // Give Glide a Uri -> ByteBuffer path so attachment-preview loads can hand bytes straight
        // to penfeizhou's ByteBufferAnimationDecoder without us blocking the main thread to read
        // them ourselves. The fetcher runs on Glide's source executor.
        registry.prepend(Uri::class.java, ByteBuffer::class.java, UriByteBufferLoaderFactory(context))
    }
}
