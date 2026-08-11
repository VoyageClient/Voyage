/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.multipicker.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import timber.log.Timber

// android.util.Size is API 21+; this local equivalent keeps multipicker working on KitKat.
data class ImageSize(val width: Int, val height: Int)

object ImageUtils {

    fun getBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val listener = ImageDecoder.OnHeaderDecodedListener { decoder, _, _ ->
                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
                        // Allocating hardware bitmap may cause a crash on framework versions prior to Android Q
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                }

                ImageDecoder.decodeBitmap(source, listener)
            } else {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Cannot decode Bitmap: %s", uri.toString())
            null
        }
    }

    /**
     * Cheap width/height probe that doesn't allocate a full bitmap.
     */
    fun getImageSize(context: Context, uri: Uri): ImageSize? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            ImageSize(bounds.outWidth, bounds.outHeight)
        } else {
            null
        }
    }

    fun getOrientation(context: Context, uri: Uri): Int {
        var orientation = 0
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            try {
                ExifInterface(inputStream).let {
                    orientation = it.rotationDegrees
                }
            } catch (e: Exception) {
                Timber.e(e, "Cannot read orientation: %s", uri.toString())
            }
        }
        return orientation
    }
}
