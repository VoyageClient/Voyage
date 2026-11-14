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
import im.vector.lib.animatedimage.AnimatedImageFormat
import im.vector.lib.animatedimage.AnimatedImageReader
import im.vector.lib.animatedimage.AnimatedWebpEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.util.JxlSupport
import org.matrix.android.sdk.internal.util.TemporaryFileCreator
import timber.log.Timber
import java.io.File
import javax.inject.Inject

internal class ImageCompressor @Inject constructor(
        private val temporaryFileCreator: TemporaryFileCreator,
        private val coroutineDispatchers: MatrixCoroutineDispatchers
) {
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
            val format = sniffImageFormat(imageFile)
            // A size or quality the sender chose is honoured however small the file already is.
            // JPEG XL is converted whatever its size: support for it is thin enough elsewhere that
            // sending it on is worse for the recipient than re-encoding, and the sender who wants the
            // original bytes has the "send original" option.
            if (!exactSize && format != ImageSourceFormat.JXL && imageFile.length() <= SMALL_FILE_PASSTHROUGH_BYTES) {
                return@withContext CompressedImage(imageFile, mimeType = null)
            }

            when (format) {
                ImageSourceFormat.GIF -> compressAnimation(imageFile, AnimatedImageFormat.GIF, desiredWidth, desiredHeight, desiredQuality, exactSize)
                ImageSourceFormat.APNG -> compressAnimation(imageFile, AnimatedImageFormat.APNG, desiredWidth, desiredHeight, desiredQuality, exactSize)
                ImageSourceFormat.JXL -> compressJxl(imageFile, desiredWidth, desiredHeight, desiredQuality, exactSize)
                ImageSourceFormat.ANIMATED_WEBP -> if (exactSize) {
                    compressAnimation(imageFile, AnimatedImageFormat.WEBP, desiredWidth, desiredHeight, desiredQuality, exactSize)
                } else CompressedImage(imageFile, mimeType = "image/webp")
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

    private suspend fun compressJxl(imageFile: File, desiredWidth: Int, desiredHeight: Int, desiredQuality: Int, exactSize: Boolean): CompressedImage {
        if (!JxlSupport.isAvailable) return CompressedImage(imageFile, mimeType = null)
        // An animation would otherwise be decoded as its first frame alone and sent as a still.
        if ((JxlImageReader.frameCount(imageFile) ?: 1) > 1) {
            return compressAnimation(imageFile, AnimatedImageFormat.JXL, desiredWidth, desiredHeight, desiredQuality, exactSize)
        }
        val decoded = JxlImageReader.decode(imageFile, REENCODE_MAX_DIMENSION) ?: return CompressedImage(imageFile, mimeType = null)
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
        if (compressedBitmap !== sourceBitmap) sourceBitmap.recycle()
        return try {
            val (format, mimeType) = if (hasTransparency(compressedBitmap)) {
                Bitmap.CompressFormat.PNG to "image/png"
            } else {
                Bitmap.CompressFormat.JPEG to "image/jpeg"
            }
            val destinationFile = temporaryFileCreator.create()
            val encoded = runCatching {
                destinationFile.outputStream().use { compressedBitmap.compress(format, desiredQuality, it) }
            }.getOrDefault(false)
            if (encoded) {
                CompressedImage(destinationFile, mimeType = mimeType)
            } else {
                destinationFile.delete()
                CompressedImage(originalFile, mimeType = null)
            }
        } finally {
            compressedBitmap.recycle()
        }
    }

    @Suppress("LongParameterList")
    private suspend fun compressAnimation(
            originalFile: File,
            format: AnimatedImageFormat,
            desiredWidth: Int,
            desiredHeight: Int,
            desiredQuality: Int,
            exactSize: Boolean,
    ): CompressedImage {
        val destination = temporaryFileCreator.create()
        val job = currentCoroutineContext()
        var writer: AnimatedWebpEncoder.StreamWriter? = null
        var encoded = false
        var unsupportedAlpha = false
        try {
            val decoded = AnimatedImageReader.visitFrames(originalFile, format) { frame, _, _ ->
                job.ensureActive()
                val bitmap = scaleBitmapToFit(frame.bitmap, desiredWidth, desiredHeight, exactSize)
                try {
                    // The API 14 WebP encoder cannot preserve transparent animation frames.
                    unsupportedAlpha = Build.VERSION.SDK_INT < 18 && hasTransparency(bitmap)
                    check(!unsupportedAlpha)
                    val output = writer ?: AnimatedWebpEncoder.StreamWriter(destination, bitmap.width, bitmap.height, desiredQuality)
                            .also { writer = it }
                    check(output.append(listOf(AnimatedFrame(bitmap, frame.durationMs))))
                } finally {
                    if (bitmap !== frame.bitmap) bitmap.recycle()
                }
            }
            encoded = decoded && writer?.finish() == true
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            if (!unsupportedAlpha) Timber.w(failure, "Failed to compress animated image")
        } finally {
            runCatching { writer?.close() }.onFailure {
                encoded = false
                Timber.w(it, "Failed to close compressed animation")
            }
            if (!encoded || (!exactSize && destination.length() >= originalFile.length())) destination.delete()
        }
        return if (destination.exists() && encoded) CompressedImage(destination, mimeType = "image/webp")
        else CompressedImage(originalFile, mimeType = null)
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
            return Bitmap.createScaledBitmap(bitmap, width, height, true)
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
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
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
