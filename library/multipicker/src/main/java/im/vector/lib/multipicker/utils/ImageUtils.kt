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
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader

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
     * Cheap width/height probe that doesn't allocate a full bitmap. Falls back to header
     * sniffing for formats Android can't decode (currently: XPM) so they don't end up reported
     * as 0x0 on upload.
     */
    fun getImageSize(context: Context, uri: Uri): Size? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            return Size(bounds.outWidth, bounds.outHeight)
        }
        return runCatching { readXpmSize(context, uri) }.getOrNull()
    }

    private fun readXpmSize(context: Context, uri: Uri): Size? {
        // XPM3 header sits inside the first quoted string: "<w> <h> <ncolors> <cpp>".
        context.contentResolver.openInputStream(uri)?.use { input ->
            val reader = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1))
            val first = StringBuilder()
            var inString = false
            while (true) {
                val ch = reader.read()
                if (ch == -1) return null
                val c = ch.toChar()
                if (!inString) {
                    if (c == '"') inString = true
                } else {
                    if (c == '"') break
                    first.append(c)
                }
            }
            val parts = first.toString().trim().split(Regex("\\s+"))
            val w = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val h = parts.getOrNull(1)?.toIntOrNull() ?: return null
            return Size(w, h)
        }
        return null
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
