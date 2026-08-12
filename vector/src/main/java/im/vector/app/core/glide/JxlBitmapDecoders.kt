/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import com.awxkee.jxlcoder.JxlAnimatedImage
import com.awxkee.jxlcoder.JxlCoder
import com.awxkee.jxlcoder.JxlResizeFilter
import com.awxkee.jxlcoder.JxlToneMapper
import com.awxkee.jxlcoder.PreferredColorConfig
import com.awxkee.jxlcoder.ScaleMode
import com.awxkee.jxlcoder.animation.AnimatedDrawable
import com.awxkee.jxlcoder.animation.JxlAnimatedStore
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapResource
import com.bumptech.glide.load.resource.bitmap.Downsampler
import com.bumptech.glide.request.target.Target
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.sqrt

internal const val JXL_MAGIC_PEEK_BYTES = 12

/**
 * mark() replaces the stream's mark *and* its read limit, and these decoders run before every other
 * animated decoder. Promising only the dozen bytes we peek would make the next decoder's reset()
 * fail once it read past them, breaking GIF/WebP/APNG playback outright.
 */
internal const val JXL_SNIFF_MARK_BYTES = 8 * 1024

/**
 * Decoding entry points for JPEG XL. We register these instead of the jxl-coder-glide plugin, which
 * passes Glide's requested size straight to the native resizer: for an image smaller than the view
 * that upscales through a cubic filter, whose overshoot leaves a bright fringe down the right edge.
 * Nothing here ever scales up.
 */
internal object JxlBitmaps {

    /** Decodes to at most [maxPixels] total pixels, preserving aspect. Null if the bytes aren't JXL. */
    fun decodeBounded(bytes: ByteArray, maxPixels: Int): Bitmap? {
        if (!JxlHeader.isJxl(bytes, minOf(JXL_MAGIC_PEEK_BYTES, bytes.size))) return null
        val size = readSize(bytes) ?: return null
        val pixels = size.first.toLong() * size.second
        val scale = if (pixels > maxPixels) sqrt(maxPixels.toDouble() / pixels).toFloat() else 1f
        return decode(bytes, scaled(size.first, scale), scaled(size.second, scale), PreferredColorConfig.DEFAULT)
    }

    /** Bounded by the requested box rather than a pixel count, for Glide's decoders. */
    fun decodeForRequest(bytes: ByteArray, requestedWidth: Int, requestedHeight: Int, options: Options): Bitmap? {
        val (srcWidth, srcHeight) = readSize(bytes) ?: return null
        val (targetWidth, targetHeight) = targetSize(srcWidth, srcHeight, requestedWidth, requestedHeight)
        return decode(bytes, targetWidth, targetHeight, preferredColorConfig(options))
    }

    /** Frame count, or null if the bytes can't be opened as JPEG XL. 1 for a still image. */
    fun frameCount(bytes: ByteArray): Int? {
        return try {
            openAnimation(bytes).use { it.numberOfFrames }
        } catch (t: Throwable) {
            Timber.w(t, "Unable to read JPEG XL frame count")
            null
        }
    }

    /**
     * A drawable over its own decoder, so several targets showing the same animation each keep their
     * own frame state. Frames are pulled lazily, bounded by the requested box. The decoder is owned
     * by the drawable and outlives this call, so it is deliberately not closed here.
     */
    fun animatedDrawable(bytes: ByteArray, requestedWidth: Int, requestedHeight: Int): Drawable? {
        return try {
            val animation = openAnimation(bytes)
            val (targetWidth, targetHeight) = targetSize(animation.getWidth(), animation.getHeight(), requestedWidth, requestedHeight)
            AnimatedDrawable(JxlAnimatedStore(animation, targetWidth, targetHeight))
        } catch (t: Throwable) {
            Timber.w(t, "Unable to open JPEG XL animation")
            null
        }
    }

    private fun openAnimation(bytes: ByteArray) = JxlAnimatedImage(
            bytes,
            PreferredColorConfig.DEFAULT,
            ScaleMode.FIT,
            JxlResizeFilter.BILINEAR,
            JxlToneMapper.REC2408,
    )

    private fun decode(bytes: ByteArray, width: Int, height: Int, colorConfig: PreferredColorConfig): Bitmap? {
        return try {
            JxlCoder.decodeSampled(
                    bytes,
                    width,
                    height,
                    colorConfig,
                    ScaleMode.FIT,
                    // CATMULL_ROM (the upstream plugin's choice) is a cubic filter and markedly slower.
                    JxlResizeFilter.BILINEAR,
                    JxlToneMapper.REC2408,
            )
        } catch (t: Throwable) {
            Timber.w(t, "Unable to decode JPEG XL")
            null
        }
    }

    private fun readSize(bytes: ByteArray): Pair<Int, Int>? {
        return try {
            JxlCoder.getSize(bytes)?.let { it.width to it.height }
        } catch (t: Throwable) {
            Timber.w(t, "Unable to read JPEG XL dimensions")
            null
        }
    }

    private fun scaled(value: Int, scale: Float) = (value * scale).toInt().coerceAtLeast(1)

    /** Aspect-preserving box fit. Returns the source size when Glide asks for the original. */
    private fun targetSize(srcWidth: Int, srcHeight: Int, requestedWidth: Int, requestedHeight: Int): Pair<Int, Int> {
        if (srcWidth <= 0 || srcHeight <= 0) return srcWidth to srcHeight
        val boundedWidth = requestedWidth.takeIf { it != Target.SIZE_ORIGINAL && it > 0 } ?: return srcWidth to srcHeight
        val boundedHeight = requestedHeight.takeIf { it != Target.SIZE_ORIGINAL && it > 0 } ?: return srcWidth to srcHeight
        val scale = minOf(boundedWidth.toFloat() / srcWidth, boundedHeight.toFloat() / srcHeight)
        if (scale >= 1f) return srcWidth to srcHeight
        return scaled(srcWidth, scale) to scaled(srcHeight, scale)
    }

    private fun preferredColorConfig(options: Options): PreferredColorConfig {
        val allowHardware = options.get(Downsampler.ALLOW_HARDWARE_CONFIG) ?: false
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && allowHardware -> PreferredColorConfig.HARDWARE
            options.get(Downsampler.DECODE_FORMAT) == DecodeFormat.PREFER_RGB_565 -> PreferredColorConfig.RGB_565
            else -> PreferredColorConfig.DEFAULT
        }
    }
}

internal object JxlHeader {

    fun isJxl(head: ByteArray, length: Int): Boolean {
        // Bare codestream.
        if (length >= 2 && head[0] == 0xFF.toByte() && head[1] == 0x0A.toByte()) return true
        // ISOBMFF container: a 12-byte JXL signature box.
        return length >= JXL_MAGIC_PEEK_BYTES &&
                head[0] == 0x00.toByte() && head[1] == 0x00.toByte() &&
                head[2] == 0x00.toByte() && head[3] == 0x0C.toByte() &&
                head[4] == 'J'.code.toByte() && head[5] == 'X'.code.toByte() &&
                head[6] == 'L'.code.toByte() && head[7] == ' '.code.toByte() &&
                head[8] == 0x0D.toByte() && head[9] == 0x0A.toByte() &&
                head[10] == 0x87.toByte() && head[11] == 0x0A.toByte()
    }
}

internal class JxlByteBufferBitmapDecoder(private val bitmapPool: BitmapPool) : ResourceDecoder<ByteBuffer, Bitmap> {

    override fun handles(source: ByteBuffer, options: Options): Boolean {
        val length = minOf(JXL_MAGIC_PEEK_BYTES, source.remaining())
        val head = ByteArray(length)
        source.duplicate().get(head, 0, length)
        return JxlHeader.isJxl(head, length)
    }

    override fun decode(source: ByteBuffer, width: Int, height: Int, options: Options): Resource<Bitmap>? {
        val bytes = ByteArray(source.remaining())
        source.duplicate().get(bytes)
        val bitmap = JxlBitmaps.decodeForRequest(bytes, width, height, options) ?: return null
        return BitmapResource.obtain(bitmap, bitmapPool)
    }
}

internal class JxlStreamBitmapDecoder(private val bitmapPool: BitmapPool) : ResourceDecoder<InputStream, Bitmap> {

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

    override fun decode(source: InputStream, width: Int, height: Int, options: Options): Resource<Bitmap>? {
        val buffer = ByteArrayOutputStream()
        source.copyTo(buffer)
        val bitmap = JxlBitmaps.decodeForRequest(buffer.toByteArray(), width, height, options) ?: return null
        return BitmapResource.obtain(bitmap, bitmapPool)
    }
}
