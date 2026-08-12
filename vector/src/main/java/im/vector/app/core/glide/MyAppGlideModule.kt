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
import android.os.Build
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
        // Glide gives every animated image in the app 2 frame-decoding threads at most, and 1 below a
        // quad-core (calculateAnimationExecutorThreadCount). Several GIFs on screen then queue their
        // frames behind one another and visibly drift off their own frame delays.
        builder.setAnimationExecutor(
                GlideExecutor.newAnimationBuilder()
                        .setThreadCount(animationThreadCount())
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
        // a separate class. Non-JXL bytes fall through their own header check.
        if (JxlSupport.isAvailable) {
            JxlGlideRegistrar.register(glide, registry)
        }
        // GIF through the platform's native decoder where there is one — Glide's own is pure-Java and
        // is what makes several GIFs on a screen stutter. Kept behind its own class so the API 28
        // types are never resolved on the old devices that keep the pure-Java path.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PlatformGifRegistrar.register(registry)
        }
    }

    private fun animationThreadCount() = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_ANIMATION_THREADS)

    companion object {
        private const val DISK_CACHE_THREADS = 3
        private const val MAX_ANIMATION_THREADS = 4
    }
}
