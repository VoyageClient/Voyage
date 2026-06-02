/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import android.graphics.Bitmap
import timber.log.Timber
import java.io.DataInputStream
import java.io.File

internal object FarbfeldBitmapReader {

    private const val MAGIC = "farbfeld"

    fun decode(file: File): Bitmap? {
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val header = ByteArray(MAGIC.length)
                input.readFully(header)
                if (String(header, Charsets.US_ASCII) != MAGIC) return null
                val w = input.readInt()
                val h = input.readInt()
                if (w <= 0 || h <= 0) return null
                val pixels = IntArray(w * h)
                val rowBytes = ByteArray(w * 8)
                for (y in 0 until h) {
                    input.readFully(rowBytes)
                    var off = 0
                    var i = y * w
                    for (x in 0 until w) {
                        val r = rowBytes[off].toInt() and 0xff
                        val g = rowBytes[off + 2].toInt() and 0xff
                        val b = rowBytes[off + 4].toInt() and 0xff
                        val a = rowBytes[off + 6].toInt() and 0xff
                        pixels[i++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        off += 8
                    }
                }
                Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            }
        } catch (t: Throwable) {
            Timber.w(t, "Farbfeld: decode failed")
            null
        }
    }

}
