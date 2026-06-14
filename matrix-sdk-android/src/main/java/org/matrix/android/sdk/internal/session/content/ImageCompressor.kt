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

    suspend fun compress(
            imageFile: File,
            desiredWidth: Int,
            desiredHeight: Int,
            desiredQuality: Int = 80,
    ): CompressedImage {
        return withContext(coroutineDispatchers.io) {
            if (imageFile.length() <= SMALL_FILE_PASSTHROUGH_BYTES) {
                return@withContext CompressedImage(imageFile, mimeType = null)
            }

            val format = sniffFormat(imageFile)
            when (format) {
                SourceFormat.GIF -> compressGif(imageFile, desiredWidth, desiredHeight, desiredQuality)
                SourceFormat.APNG -> compressApng(imageFile, desiredWidth, desiredHeight, desiredQuality)
                SourceFormat.XPM -> compressXpm(imageFile, desiredWidth, desiredHeight, desiredQuality)
                SourceFormat.FARBFELD -> compressFarbfeld(imageFile, desiredWidth, desiredHeight, desiredQuality)
                // Re-encoding would drop to the first frame and strip the animation.
                SourceFormat.ANIMATED_WEBP -> CompressedImage(imageFile, mimeType = "image/webp")
                SourceFormat.OTHER -> compressBitmap(imageFile, desiredWidth, desiredHeight, desiredQuality)
            }
        }
    }

    private suspend fun compressBitmap(imageFile: File, desiredWidth: Int, desiredHeight: Int, desiredQuality: Int): CompressedImage {
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
        return encodeBitmapToWebp(imageFile, downsampled, desiredWidth, desiredHeight, desiredQuality)
    }

    private suspend fun compressXpm(imageFile: File, desiredWidth: Int, desiredHeight: Int, desiredQuality: Int): CompressedImage {
        val decoded = XpmBitmapReader.decode(imageFile) ?: return CompressedImage(imageFile, mimeType = null)
        return encodeBitmapToWebp(imageFile, decoded, desiredWidth, desiredHeight, desiredQuality)
    }

    private suspend fun compressFarbfeld(imageFile: File, desiredWidth: Int, desiredHeight: Int, desiredQuality: Int): CompressedImage {
        val decoded = FarbfeldBitmapReader.decode(imageFile) ?: return CompressedImage(imageFile, mimeType = null)
        return encodeBitmapToWebp(imageFile, decoded, desiredWidth, desiredHeight, desiredQuality)
    }

    private suspend fun encodeBitmapToWebp(originalFile: File, sourceBitmap: Bitmap, desiredWidth: Int, desiredHeight: Int, desiredQuality: Int): CompressedImage {
        val compressedBitmap = scaleBitmapToFit(sourceBitmap, desiredWidth, desiredHeight)
        val format = webpLossyFormat()
        val destinationFile = temporaryFileCreator.create()
        runCatching {
            destinationFile.outputStream().use {
                compressedBitmap.compress(format, desiredQuality, it)
            }
        }.onFailure {
            return CompressedImage(originalFile, mimeType = null)
        }
        return CompressedImage(destinationFile, mimeType = "image/webp")
    }

    private suspend fun compressGif(imageFile: File, desiredWidth: Int, desiredHeight: Int, desiredQuality: Int): CompressedImage {
        val frames = GifFrameReader.readFrames(imageFile) ?: return CompressedImage(imageFile, mimeType = null)
        return encodeFramesToAnimatedWebp(imageFile, frames, desiredWidth, desiredHeight, desiredQuality)
    }

    private suspend fun compressApng(imageFile: File, desiredWidth: Int, desiredHeight: Int, desiredQuality: Int): CompressedImage {
        val frames = ApngFrameReader.readFrames(imageFile) ?: return CompressedImage(imageFile, mimeType = null)
        return encodeFramesToAnimatedWebp(imageFile, frames, desiredWidth, desiredHeight, desiredQuality)
    }

    private suspend fun encodeFramesToAnimatedWebp(
            originalFile: File,
            frames: List<AnimatedFrame>,
            desiredWidth: Int,
            desiredHeight: Int,
            desiredQuality: Int,
    ): CompressedImage {
        if (frames.isEmpty()) return CompressedImage(originalFile, mimeType = null)
        // ANMF requires uniform canvas dims; scale every frame to fit the same bounds.
        val scaled = frames.map { frame ->
            val out = scaleBitmapToFit(frame.bitmap, desiredWidth, desiredHeight)
            if (out !== frame.bitmap) frame.bitmap.recycle()
            AnimatedFrame(out, frame.durationMs)
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

    private enum class SourceFormat { GIF, APNG, XPM, FARBFELD, ANIMATED_WEBP, OTHER }

    private fun sniffFormat(file: File): SourceFormat {
        val head = ByteArray(64)
        val read = try {
            file.inputStream().use { it.read(head) }
        } catch (t: Throwable) {
            return SourceFormat.OTHER
        }
        if (read < 8) return SourceFormat.OTHER
        // GIF: "GIF87a" or "GIF89a"
        if (head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte()) return SourceFormat.GIF
        // PNG signature; differentiate APNG by presence of the acTL chunk in the first ~64 bytes.
        if (read >= 8 &&
                head[0] == 0x89.toByte() && head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()) {
            // acTL must appear before IDAT — scan the whole file's first ~4 KB to detect.
            return if (containsApngMarker(file)) SourceFormat.APNG else SourceFormat.OTHER
        }
        // RIFF....WEBP — VP8X header at offset 12 carries the ANIM flag (bit 1) when animated.
        if (read >= 21 &&
                head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() &&
                head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte() && head[10] == 'B'.code.toByte() && head[11] == 'P'.code.toByte() &&
                head[12] == 'V'.code.toByte() && head[13] == 'P'.code.toByte() && head[14] == '8'.code.toByte() && head[15] == 'X'.code.toByte() &&
                (head[20].toInt() and (1 shl 1)) != 0) {
            return SourceFormat.ANIMATED_WEBP
        }
        if (read >= 9 && String(head, 0, 9, Charsets.US_ASCII).startsWith("/* XPM */")) return SourceFormat.XPM
        if (read >= 8 && String(head, 0, 8, Charsets.US_ASCII) == "farbfeld") return SourceFormat.FARBFELD
        return SourceFormat.OTHER
    }

    private fun containsApngMarker(file: File): Boolean {
        // acTL chunk is required for APNG and must come before the first IDAT.
        val buf = ByteArray(4096)
        return try {
            file.inputStream().use {
                val n = it.read(buf)
                if (n <= 0) return@use false
                val needle = "acTL".toByteArray(Charsets.US_ASCII)
                var i = 0
                while (i <= n - needle.size) {
                    var match = true
                    for (k in needle.indices) {
                        if (buf[i + k] != needle[k]) { match = false; break }
                    }
                    if (match) return@use true
                    i++
                }
                false
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun scaleBitmapToFit(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
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
    }
}
