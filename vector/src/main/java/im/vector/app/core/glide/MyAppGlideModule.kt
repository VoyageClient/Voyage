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
import com.bumptech.glide.load.engine.executor.GlideExecutor
import com.bumptech.glide.module.AppGlideModule
import im.vector.app.features.media.ImageContentRenderer
import org.matrix.android.sdk.api.util.JxlSupport
import java.io.InputStream
import java.nio.ByteBuffer

@GlideModule
class MyAppGlideModule : AppGlideModule() {

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        builder.setLogLevel(Log.ERROR)
        // Every request that consults the disk cache — which is all timeline thumbnails and all
        // remote loads — runs on this executor, and Glide's default gives it a single thread. One
        // slow or wedged decode there stalls media app-wide until the process restarts.
        builder.setDiskCacheExecutor(
                GlideExecutor.newDiskCacheBuilder()
                        .setThreadCount(DISK_CACHE_THREADS)
                        .build()
        )
    }

    // zjupure's webpdecoder ships both an annotation @GlideModule and a legacy manifest GlideModule;
    // with the annotation processor active, parsing the manifest too would register it twice.
    override fun isManifestParsingEnabled(): Boolean = false

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
        // APNG via penfeizhou — see AnimatedDrawableDecoder. Registered for both ByteBuffer (disk
        // cache) and InputStream (the timeline's custom data loader) so every source reaches
        // penfeizhou rather than Glide's bundled decoders, which miss some APNG headers and fall back
        // to a single still frame. WebP (all variants) is owned by zjupure's libwebp decoder, which
        // this prepend sits ahead of — so APNG is matched here and WebP falls through to zjupure.
        val byteBufferAnimated = AnimatedDrawableDecoder()
        registry.prepend(ByteBuffer::class.java, Drawable::class.java, byteBufferAnimated)
        registry.prepend(InputStream::class.java, Drawable::class.java, AnimatedStreamDrawableDecoder(byteBufferAnimated))
        // Give Glide a Uri -> ByteBuffer path so attachment-preview loads can hand bytes straight
        // to penfeizhou's ByteBufferAnimationDecoder without us blocking the main thread to read
        // them ourselves. The fetcher runs on Glide's source executor.
        registry.prepend(Uri::class.java, ByteBuffer::class.java, UriByteBufferLoaderFactory(context))
        // JPEG XL, via our own decoders rather than the jxl-coder-glide plugin — see JxlBitmaps. They
        // load libjxl the moment they are constructed, so the registration is gated and lives behind
        // a separate class. Both produce Bitmap, so they sit alongside the Drawable decoders above
        // rather than competing with them, and non-JXL bytes fall through their own header check.
        if (JxlSupport.isAvailable) {
            JxlGlideRegistrar.register(glide, registry)
        }
    }

    companion object {
        private const val DISK_CACHE_THREADS = 3
    }
}
