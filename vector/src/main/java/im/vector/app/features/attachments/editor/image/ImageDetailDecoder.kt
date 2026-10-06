/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import im.vector.lib.multipicker.utils.ImageUtils
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Re-decodes the part of the source on screen at the resolution a zoom needs, since the editor's
 * own bitmap is only screen-sized. Rectangles are normalised against the EXIF-rotated image.
 */
class ImageDetailDecoder private constructor(
        private val context: Context,
        private val source: Uri,
        private val regionDecoder: BitmapRegionDecoder?,
        private val rotation: Int,
        private val fullWidth: Int,
        private val baseWidth: Int,
) {

    class Detail(val bitmap: Bitmap, val rect: RectF, val sample: Int)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0
    @Volatile private var closed = false

    /** Only formats BitmapRegionDecoder rejects need this; it never changes, so it is decoded once. */
    private var whole: Detail? = null
    private var wholeUseless = false

    /** The subsampling that matches [displayedWidth] screen pixels across the image, or null when the base bitmap already does. */
    fun sampleFor(displayedWidth: Float): Int? {
        if (displayedWidth <= 0f) return null
        val sample = powerOfTwoAtMost(fullWidth / displayedWidth)
        return sample.takeIf { improvesOnBase(it) }
    }

    /** [maxSize] is the largest side the canvas can draw; a hardware canvas refuses anything bigger. */
    fun request(rect: RectF, sample: Int, maxSize: Int, onResult: (Detail?) -> Unit) {
        if (closed) return
        val token = ++generation
        val wanted = RectF(rect)
        executor.execute {
            if (token != generation || closed) return@execute
            val detail = try {
                decode(wanted, sample, maxSize)
            } catch (error: OutOfMemoryError) {
                Timber.w(error, "Image editor: out of memory decoding detail")
                null
            } catch (error: Exception) {
                Timber.w(error, "Image editor: detail decode failed")
                null
            }
            mainHandler.post { if (token == generation && !closed) onResult(detail) }
        }
    }

    fun close() {
        closed = true
        generation++
        executor.execute { regionDecoder?.recycle() }
        executor.shutdown()
    }

    private fun improvesOnBase(sample: Int) = fullWidth / sample > baseWidth * MIN_GAIN

    private fun decode(rect: RectF, sample: Int, maxSize: Int): Detail? {
        val decoder = regionDecoder ?: return decodeWhole(sample, maxSize)
        val rawWidth = decoder.width
        val rawHeight = decoder.height
        val raw = orientedToRaw(rect, rotation)
        val pixels = Rect(
                floor(raw.left * rawWidth).toInt().coerceIn(0, rawWidth),
                floor(raw.top * rawHeight).toInt().coerceIn(0, rawHeight),
                ceil(raw.right * rawWidth).toInt().coerceIn(0, rawWidth),
                ceil(raw.bottom * rawHeight).toInt().coerceIn(0, rawHeight),
        )
        if (pixels.width() <= 0 || pixels.height() <= 0) return null
        var actualSample = sample
        val budget = pixelBudget()
        while (pixels.width().toLong() * pixels.height() / (actualSample.toLong() * actualSample) > budget ||
                maxOf(pixels.width(), pixels.height()) / actualSample > maxSize) {
            actualSample *= 2
        }
        if (!improvesOnBase(actualSample)) return null
        val decoded = decoder.decodeRegion(pixels, BitmapFactory.Options().apply { inSampleSize = actualSample }) ?: return null
        val decodedRect = RectF(
                pixels.left.toFloat() / rawWidth,
                pixels.top.toFloat() / rawHeight,
                pixels.right.toFloat() / rawWidth,
                pixels.bottom.toFloat() / rawHeight,
        )
        return Detail(ImageEditorExporter.applyRotation(decoded, rotation), rawToOriented(decodedRect, rotation), sample)
    }

    private fun decodeWhole(sample: Int, maxSize: Int): Detail? {
        whole?.let { return Detail(it.bitmap, it.rect, sample) }
        if (wholeUseless) return null
        // Only bounds the side of a square image; a longer one past maxSize fails to draw, leaving the base.
        val budget = minOf(pixelBudget().toLong(), maxSize.toLong() * maxSize).toInt()
        val bitmap = ImageEditorExporter.loadOriented(context, source, budget)
        if (bitmap == null || bitmap.width <= baseWidth * MIN_GAIN) {
            wholeUseless = true
            return null
        }
        return Detail(bitmap, RectF(0f, 0f, 1f, 1f), sample).also { whole = it }
    }

    companion object {
        /** Re-decoding for less than this much extra sharpness is not worth the memory. */
        private const val MIN_GAIN = 1.15f
        private const val BYTES_PER_PIXEL = 4
        private const val MAX_SAMPLE = 1 shl 30

        /** The base bitmap and the outgoing detail are still alive while a new one decodes. */
        private fun pixelBudget(): Int = (Runtime.getRuntime().maxMemory() / 8 / BYTES_PER_PIXEL).toInt()

        /** Null when the source is not larger than [base], which then already shows every pixel there is. */
        fun create(context: Context, source: Uri, base: Bitmap): ImageDetailDecoder? {
            val rotation = ImageUtils.getOrientation(context, source).mod(360)
            val regionDecoder = try {
                // The replacement overload is API 31; this one works on every version.
                @Suppress("DEPRECATION")
                context.contentResolver.openInputStream(source)?.use { BitmapRegionDecoder.newInstance(it, false) }
            } catch (error: Exception) {
                Timber.d(error, "Image editor: no region decoder for this format")
                null
            }
            val sideways = rotation % 180 != 0
            val fullWidth = when {
                regionDecoder != null -> if (sideways) regionDecoder.height else regionDecoder.width
                else -> {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    // A format BitmapFactory cannot size (JPEG XL) gets one larger decode; decodeWhole judges the gain.
                    if (bounds.outWidth > 0) (if (sideways) bounds.outHeight else bounds.outWidth) else Int.MAX_VALUE
                }
            }
            if (fullWidth <= base.width * MIN_GAIN) {
                regionDecoder?.recycle()
                return null
            }
            return ImageDetailDecoder(context.applicationContext, source, regionDecoder, rotation, fullWidth, base.width)
        }

        internal fun powerOfTwoAtMost(value: Float): Int {
            var sample = 1
            while (sample < MAX_SAMPLE && sample * 2 <= value) sample *= 2
            return sample
        }

        /** [rect] normalised against the image as shown, mapped onto the file's own pixel grid. */
        internal fun orientedToRaw(rect: RectF, rotation: Int): RectF = when (rotation) {
            90 -> RectF(rect.top, 1f - rect.right, rect.bottom, 1f - rect.left)
            180 -> RectF(1f - rect.right, 1f - rect.bottom, 1f - rect.left, 1f - rect.top)
            270 -> RectF(1f - rect.bottom, rect.left, 1f - rect.top, rect.right)
            else -> RectF(rect)
        }

        internal fun rawToOriented(rect: RectF, rotation: Int): RectF = when (rotation) {
            90 -> RectF(1f - rect.bottom, rect.left, 1f - rect.top, rect.right)
            180 -> RectF(1f - rect.right, 1f - rect.bottom, 1f - rect.left, 1f - rect.top)
            270 -> RectF(rect.top, 1f - rect.right, rect.bottom, 1f - rect.left)
            else -> RectF(rect)
        }
    }
}
