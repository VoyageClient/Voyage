/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.media

import android.graphics.BitmapFactory
import javax.inject.Inject

internal class AndroidImageDimensionsReader @Inject constructor() : ImageDimensionsReader {

    override fun read(bytes: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return (options.outWidth to options.outHeight).takeIf { it.first > 0 && it.second > 0 }
    }
}
