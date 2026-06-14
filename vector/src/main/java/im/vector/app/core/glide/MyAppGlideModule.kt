/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.content.Context
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
        registry.prepend(InputStream::class.java, Drawable::class.java, SvgDecoder())
        // Animated WebP / APNG / GIF via penfeizhou — see AnimatedDrawableDecoder. Registered for
        // both ByteBuffer (disk cache) and InputStream (the timeline's custom data loader) so every
        // animated source reaches penfeizhou rather than Glide's bundled decoders, which miss some
        // animated WebP/APNG headers and fall back to a single still frame.
        val byteBufferAnimated = AnimatedDrawableDecoder()
        registry.prepend(ByteBuffer::class.java, Drawable::class.java, byteBufferAnimated)
        registry.prepend(InputStream::class.java, Drawable::class.java, AnimatedStreamDrawableDecoder(byteBufferAnimated))
        // Give Glide a Uri -> ByteBuffer path so attachment-preview loads can hand bytes straight
        // to penfeizhou's ByteBufferAnimationDecoder without us blocking the main thread to read
        // them ourselves. The fetcher runs on Glide's source executor.
        registry.prepend(Uri::class.java, ByteBuffer::class.java, UriByteBufferLoaderFactory(context))
    }
}
