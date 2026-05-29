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
            // Skip compression entirely on small files — re-encoding rarely pays for itself.
            if (imageFile.length() <= SMALL_FILE_PASSTHROUGH_BYTES) {
                return@withContext CompressedImage(imageFile, mimeType = null)
            }

            // Probe dimensions without decoding the pixel data.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            decodeBitmap(imageFile, bounds)
            val srcWidth = bounds.outWidth
            val srcHeight = bounds.outHeight
            if (srcWidth <= 0 || srcHeight <= 0) {
                // Couldn't decode bounds — fall back to returning the original file rather
                // than re-encoding garbage.
                return@withContext CompressedImage(imageFile, mimeType = null)
            }

            val downsampleOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(srcWidth, srcHeight, desiredWidth, desiredHeight)
                inJustDecodeBounds = false
            }
            val downsampled = decodeBitmap(imageFile, downsampleOptions)?.let {
                rotateBitmap(imageFile, it)
            } ?: return@withContext CompressedImage(imageFile, mimeType = null)

            // inSampleSize only produces power-of-2 reductions, so the decoded bitmap may
            // still be significantly larger than the requested bounds. Scale it down to fit
            // the desired box while preserving aspect ratio so the uploaded file is not
            // unexpectedly huge.
            val compressedBitmap = scaleBitmapToFit(downsampled, desiredWidth, desiredHeight)

            // WebP gives ~30% smaller files than JPEG at equal quality and preserves alpha.
            // WEBP_LOSSY (API 30+) handles transparency directly; on older devices fall back to
            // the deprecated WEBP constant which is also lossy.
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }

            val destinationFile = temporaryFileCreator.create()
            runCatching {
                destinationFile.outputStream().use {
                    compressedBitmap.compress(format, desiredQuality, it)
                }
            }.onFailure {
                return@withContext CompressedImage(imageFile, mimeType = null)
            }

            CompressedImage(destinationFile, mimeType = "image/webp")
        }
    }

    private fun scaleBitmapToFit(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        if (bitmap.width <= maxWidth && bitmap.height <= maxHeight) return bitmap
        val scale = minOf(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height)
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
