/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.content

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import im.vector.lib.animatedimage.AnimatedFrame
import im.vector.lib.animatedimage.AnimatedWebpEncoder
import im.vector.lib.animatedimage.ApngFrameReader
import im.vector.lib.animatedimage.GifFrameReader
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.internal.util.TemporaryFileCreator
import timber.log.Timber
import java.io.File
import javax.inject.Inject

internal class ImageCompressor @Inject constructor(
        private val temporaryFileCreator: TemporaryFileCreator,
        private val coroutineDispatchers: MatrixCoroutineDispatchers
) {
    data class CompressedImage(val file: File, val mimeType: String?)

    /**
     * @param exactSize scales to exactly [desiredWidth] x [desiredHeight] rather than bounding the
     * shorter side, for a size the sender typed in themselves.
     */
    suspend fun compress(
            imageFile: File,
            desiredWidth: Int,
            desiredHeight: Int,
            desiredQuality: Int = 80,
            exactSize: Boolean = false,
    ): CompressedImage {
        return withContext(coroutineDispatchers.io) {
            // A size or quality the sender chose is honoured however small the file already is.
            if (!exactSize && imageFile.length() <= SMALL_FILE_PASSTHROUGH_BYTES) {
                return@withContext CompressedImage(imageFile, mimeType = null)
            }

            val format = sniffImageFormat(imageFile)
            when (format) {
                ImageSourceFormat.GIF -> compressGif(imageFile, desiredWidth, desiredHeight, desiredQuality, exactSize)
                ImageSourceFormat.APNG -> compressApng(imageFile, desiredWidth, desiredHeight, desiredQuality, exactSize)
                ImageSourceFormat.XPM -> compressXpm(imageFile, desiredWidth, desiredHeight, desiredQuality, exactSize)
                ImageSourceFormat.FARBFELD -> compressFarbfeld(imageFile, desiredWidth, desiredHeight, desiredQuality, exactSize)
                // Re-encoding would drop to the first frame and strip the animation.
                ImageSourceFormat.ANIMATED_WEBP -> CompressedImage(imageFile, mimeType = "image/webp")
                // Platform WebP has no alpha/lossless support before 4.2.1: decoding either fails
                // or bakes transparency to black, so keep the original bytes there.
                ImageSourceFormat.STATIC_WEBP_ALPHA ->
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
                        CompressedImage(imageFile, mimeType = "image/webp")
                    } else {
                        compressBitmap(imageFile, desiredWidth, desiredHeight, desiredQuality, exactSize)
                    }
                ImageSourceFormat.OTHER -> compressBitmap(imageFile, desiredWidth, desiredHeight, desiredQuality, exactSize)
            }
        }
    }

    /**
     * Always decode + re-encode (no small-file passthrough), which strips every trace of embedded
     * metadata. Used for formats whose metadata can't be scrubbed in place (e.g. HEIC). Orientation
     * is baked into the pixels, so no EXIF is needed to display it correctly. Output is bounded to
     * [REENCODE_MAX_DIMENSION] on the shorter side to keep a full-resolution decode from OOMing.
     */
    suspend fun reEncodeStrippingMetadata(imageFile: File, desiredQuality: Int = 90): CompressedImage {
        return withContext(coroutineDispatchers.io) {
            try {
                compressBitmap(imageFile, REENCODE_MAX_DIMENSION, REENCODE_MAX_DIMENSION, desiredQuality)
            } catch (t: Throwable) {
                Timber.w(t, "Metadata-stripping re-encode failed")
                CompressedImage(imageFile, mimeType = null)
            }
        }
    }

    private suspend fun compressBitmap(
            imageFile: File,
            desiredWidth: Int,
            desiredHeight: Int,
            desiredQuality: Int,
            exactSize: Boolean = false,
    ): CompressedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decodeBitmap(imageFile, bounds)
        val srcWidth = bounds.outWidth
        val srcHeight = bounds.outHeight
        if (srcWidth <= 0 || srcHeight <= 0) {
            return CompressedImage(imageFile, mimeType = null)
        }
        val downsampleOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(srcWidth, srcHeight, desiredWidth, desiredHeight)
            inJustDecodeBounds = false
        }
        val downsampled = decodeBitmap(imageFile, downsampleOptions)?.let {
            rotateBitmap(imageFile, it)
        } ?: return CompressedImage(imageFile, mimeType = null)
        return encodeBitmap(imageFile, downsampled, desiredWidth, desiredHeight, desiredQuality, exactSize)
    }

    private suspend fun compressXpm(imageFile: File, desiredWidth: Int, desiredHeight: Int, desiredQuality: Int, exactSize: Boolean): CompressedImage {
        val decoded = XpmBitmapReader.decode(imageFile) ?: return CompressedImage(imageFile, mimeType = null)
        return encodeBitmap(imageFile, decoded, desiredWidth, desiredHeight, desiredQuality, exactSize)
    }

    private suspend fun compressFarbfeld(imageFile: File, desiredWidth: Int, desiredHeight: Int, desiredQuality: Int, exactSize: Boolean): CompressedImage {
        val decoded = FarbfeldBitmapReader.decode(imageFile) ?: return CompressedImage(imageFile, mimeType = null)
        return encodeBitmap(imageFile, decoded, desiredWidth, desiredHeight, desiredQuality, exactSize)
    }

    @Suppress("LongParameterList")
    private suspend fun encodeBitmap(
            originalFile: File,
            sourceBitmap: Bitmap,
            desiredWidth: Int,
            desiredHeight: Int,
            desiredQuality: Int,
            exactSize: Boolean,
    ): CompressedImage {
        val compressedBitmap = scaleBitmapToFit(sourceBitmap, desiredWidth, desiredHeight, exactSize)
        // Transparent images must not become WebP: homeservers thumbnail WebP as JPEG (no alpha),
        // blacking out the background wherever a server thumbnail is shown — and Bitmap.compress(WEBP)
        // can't even write alpha before 4.2.1. PNG avoids both.
        val (format, mimeType) = if (hasTransparency(compressedBitmap)) {
            Bitmap.CompressFormat.PNG to "image/png"
        } else {
            webpLossyFormat() to "image/webp"
        }
        val destinationFile = temporaryFileCreator.create()
        runCatching {
            destinationFile.outputStream().use {
                compressedBitmap.compress(format, desiredQuality, it)
            }
        }.onFailure {
            return CompressedImage(originalFile, mimeType = null)
        }
        return CompressedImage(destinationFile, mimeType = mimeType)
    }

    private suspend fun compressGif(imageFile: File, desiredWidth: Int, desiredHeight: Int, desiredQuality: Int, exactSize: Boolean): CompressedImage {
        val frames = GifFrameReader.readFrames(imageFile) ?: return CompressedImage(imageFile, mimeType = null)
        return encodeFramesToAnimatedWebp(imageFile, frames, desiredWidth, desiredHeight, desiredQuality, exactSize)
    }

    private suspend fun compressApng(imageFile: File, desiredWidth: Int, desiredHeight: Int, desiredQuality: Int, exactSize: Boolean): CompressedImage {
        val frames = ApngFrameReader.readFrames(imageFile) ?: return CompressedImage(imageFile, mimeType = null)
        return encodeFramesToAnimatedWebp(imageFile, frames, desiredWidth, desiredHeight, desiredQuality, exactSize)
    }

    @Suppress("LongParameterList")
    private suspend fun encodeFramesToAnimatedWebp(
            originalFile: File,
            frames: List<AnimatedFrame>,
            desiredWidth: Int,
            desiredHeight: Int,
            desiredQuality: Int,
            exactSize: Boolean,
    ): CompressedImage {
        if (frames.isEmpty()) return CompressedImage(originalFile, mimeType = null)
        // ANMF requires uniform canvas dims; scale every frame to fit the same bounds.
        val scaled = frames.map { frame ->
            val out = scaleBitmapToFit(frame.bitmap, desiredWidth, desiredHeight, exactSize)
            if (out !== frame.bitmap) frame.bitmap.recycle()
            AnimatedFrame(out, frame.durationMs)
        }
        // Transparent animations must not become WebP either (see encodeBitmap), and frames
        // can't individually fall back to PNG — keep the original GIF/APNG instead.
        if (scaled.any { hasTransparency(it.bitmap) }) {
            scaled.forEach { it.bitmap.recycle() }
            return CompressedImage(originalFile, mimeType = null)
        }
        val destinationFile = temporaryFileCreator.create()
        val ok = runCatching {
            destinationFile.outputStream().use { os ->
                AnimatedWebpEncoder.encode(scaled, desiredQuality, os)
            }
        }.getOrDefault(false)
        scaled.forEach { it.bitmap.recycle() }
        if (!ok) return CompressedImage(originalFile, mimeType = null)
        // Safety net: if re-encoding made the file bigger (rare with already-efficient sources),
        // throw the result away and keep the original.
        if (destinationFile.length() >= originalFile.length()) {
            destinationFile.delete()
            return CompressedImage(originalFile, mimeType = null)
        }
        return CompressedImage(destinationFile, mimeType = "image/webp")
    }

    private fun webpLossyFormat(): Bitmap.CompressFormat =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }

    // hasAlpha() alone over-reports (any ARGB_8888 bitmap has an alpha channel, e.g. opaque
    // screenshots); scan for an actually-transparent pixel, bailing out on the first hit.
    private fun hasTransparency(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        val width = bitmap.width
        val row = IntArray(width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                if (row[x] ushr 24 != 0xFF) return true
            }
        }
        return false
    }

    private fun scaleBitmapToFit(bitmap: Bitmap, maxWidth: Int, maxHeight: Int, exactSize: Boolean = false): Bitmap {
        if (exactSize) {
            val width = maxWidth.coerceAtLeast(1)
            val height = maxHeight.coerceAtLeast(1)
            if (bitmap.width == width && bitmap.height == height) return bitmap
            return Bitmap.createScaledBitmap(bitmap, width, height, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        }
        // Cap the *smaller* side to the limit (never upscaling), so the larger side scales
        // proportionally. This keeps long/wide images from being squished down to e.g. 100x640;
        // their smaller side stays at the limit (or its original value if already below it).
        val limit = minOf(maxWidth, maxHeight)
        val smallerSide = minOf(bitmap.width, bitmap.height)
        if (smallerSide <= limit) return bitmap
        val scale = limit.toFloat() / smallerSide
        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    private fun rotateBitmap(file: File, bitmap: Bitmap): Bitmap {
        file.inputStream().use { inputStream ->
            try {
                ExifInterface(inputStream).let { exifInfo ->
                    val orientation = exifInfo.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                    val matrix = Matrix()
                    when (orientation) {
                        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> {
                            matrix.preRotate(-90f)
                            matrix.preScale(-1f, 1f)
                        }
                        ExifInterface.ORIENTATION_TRANSVERSE -> {
                            matrix.preRotate(90f)
                            matrix.preScale(-1f, 1f)
                        }
                        else -> return bitmap
                    }
                    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                }
            } catch (e: Exception) {
                Timber.e(e, "Cannot read orientation")
            }
        }
        return bitmap
    }

    // https://developer.android.com/topic/performance/graphics/load-bitmap
    private fun calculateInSampleSize(width: Int, height: Int, desiredWidth: Int, desiredHeight: Int): Int {
        var inSampleSize = 1

        if (width > desiredWidth || height > desiredHeight) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while (halfHeight / inSampleSize >= desiredHeight && halfWidth / inSampleSize >= desiredWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    private fun decodeBitmap(file: File, options: BitmapFactory.Options = BitmapFactory.Options()): Bitmap? {
        return try {
            file.inputStream().use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }
        } catch (e: Exception) {
            Timber.e(e, "Cannot decode Bitmap")
            null
        }
    }

    companion object {
        private const val SMALL_FILE_PASSTHROUGH_BYTES = 512 * 1024L
        private const val REENCODE_MAX_DIMENSION = 2048
    }
}
