/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.content

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.vanniktech.blurhash.BlurHash
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.internal.session.content.blurHashComponents

object AudioCoverArt {

    fun encode(bytes: ByteArray): String? = tryOrNull {
        val art = decodeScaled(bytes) ?: return null
        try {
            val (xc, yc) = blurHashComponents(art.width, art.height)
            BlurHash.encode(art, xc, yc)
        } finally {
            art.recycle()
        }
    }

    /** BlurHash.encode runs a trig term per pixel, so never hand it a full-size cover. */
    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > COVER_MAX_DIMENSION) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private const val COVER_MAX_DIMENSION = 128
}
