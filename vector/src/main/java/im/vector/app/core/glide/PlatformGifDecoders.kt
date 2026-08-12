/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import androidx.annotation.RequiresApi
import com.bumptech.glide.Registry
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.request.target.Target
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer

private const val GIF_MAGIC_BYTES = 6
private const val GIF_SNIFF_MARK_BYTES = 8 * 1024

/**
 * GIF through the platform's native decoder.
 *
 * Glide bundles [AnimatedImageDecoder] over the same [ImageDecoder] but deliberately restricts it to
 * animated WebP and AVIF, leaving GIF on the bundled StandardGifDecoder — pure-Java LZW, decoding
 * every frame on the CPU, which is what makes several GIFs on one screen drift and stutter.
 *
 * Registered only from API 28. Below that the pure-Java decoder stays in charge, which is also what
 * keeps GIFs working on the old devices this fork targets.
 */
@RequiresApi(28)
internal object PlatformGifRegistrar {

    fun register(registry: Registry) {
        registry.prepend(Registry.BUCKET_ANIMATION, ByteBuffer::class.java, Drawable::class.java, ByteBufferPlatformGifDecoder())
        registry.prepend(Registry.BUCKET_ANIMATION, InputStream::class.java, Drawable::class.java, StreamPlatformGifDecoder())
    }
}

@RequiresApi(28)
private fun decodeAnimated(bytes: ByteArray, requestedWidth: Int, requestedHeight: Int): Resource<Drawable>? {
    val drawable = try {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.isMutableRequired = false
            val (targetWidth, targetHeight) = boundedSize(info.size.width, info.size.height, requestedWidth, requestedHeight)
            decoder.setTargetSize(targetWidth, targetHeight)
        }
    } catch (t: Throwable) {
        Timber.w(t, "Platform GIF decode failed; falling back")
        return null
    }
    // A single-frame GIF comes back as a plain bitmap drawable — leave those to the usual path.
    if (drawable !is AnimatedImageDrawable) return null
    // Glide's GifDrawable loops forever whatever the file says, so match it rather than change how
    // existing GIFs behave.
    drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
    return object : Resource<Drawable> {
        override fun getResourceClass(): Class<Drawable> = Drawable::class.java
        override fun get(): Drawable = drawable
        override fun getSize(): Int = bytes.size
        override fun recycle() = Unit
    }
}

/** Aspect-preserving box fit that never upscales. */
private fun boundedSize(srcWidth: Int, srcHeight: Int, requestedWidth: Int, requestedHeight: Int): Pair<Int, Int> {
    if (srcWidth <= 0 || srcHeight <= 0) return srcWidth to srcHeight
    val boundedWidth = requestedWidth.takeIf { it > 0 && it != Target.SIZE_ORIGINAL } ?: return srcWidth to srcHeight
    val boundedHeight = requestedHeight.takeIf { it > 0 && it != Target.SIZE_ORIGINAL } ?: return srcWidth to srcHeight
    val scale = minOf(boundedWidth.toFloat() / srcWidth, boundedHeight.toFloat() / srcHeight)
    if (scale >= 1f) return srcWidth to srcHeight
    return (srcWidth * scale).toInt().coerceAtLeast(1) to (srcHeight * scale).toInt().coerceAtLeast(1)
}

private fun isGif(head: ByteArray, length: Int): Boolean {
    return length >= GIF_MAGIC_BYTES &&
            head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() &&
            head[3] == '8'.code.toByte()
}

@RequiresApi(28)
private class ByteBufferPlatformGifDecoder : ResourceDecoder<ByteBuffer, Drawable> {

    override fun handles(source: ByteBuffer, options: Options): Boolean {
        val length = minOf(GIF_MAGIC_BYTES, source.remaining())
        val head = ByteArray(length)
        source.duplicate().get(head, 0, length)
        return isGif(head, length)
    }

    override fun decode(source: ByteBuffer, width: Int, height: Int, options: Options): Resource<Drawable>? {
        val bytes = ByteArray(source.remaining())
        source.duplicate().get(bytes)
        return decodeAnimated(bytes, width, height)
    }
}

@RequiresApi(28)
private class StreamPlatformGifDecoder : ResourceDecoder<InputStream, Drawable> {

    override fun handles(source: InputStream, options: Options): Boolean {
        if (!source.markSupported()) return false
        // Promise a generous read limit: the decoders that run after this one must still be able to
        // reset() after reading well past the few bytes peeked here.
        source.mark(GIF_SNIFF_MARK_BYTES)
        val head = ByteArray(GIF_MAGIC_BYTES)
        val length = try {
            source.read(head)
        } finally {
            source.reset()
        }
        return length > 0 && isGif(head, length)
    }

    override fun decode(source: InputStream, width: Int, height: Int, options: Options): Resource<Drawable>? {
        val buffer = ByteArrayOutputStream()
        source.copyTo(buffer)
        return decodeAnimated(buffer.toByteArray(), width, height)
    }
}
