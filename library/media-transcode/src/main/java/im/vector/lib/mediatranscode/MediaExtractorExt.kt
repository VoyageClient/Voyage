/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.RequiresApi

@RequiresApi(16)
internal fun MediaExtractor.firstTrackOf(mimePrefix: String): Int? {
    for (i in 0 until trackCount) {
        val mime = getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
        if (mime.startsWith(mimePrefix)) return i
    }
    return null
}

@RequiresApi(16)
internal fun MediaExtractor.sampleFlagsCompat(): Int {
    var flags = 0
    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
        @Suppress("DEPRECATION")
        flags = flags or MediaCodec.BUFFER_FLAG_SYNC_FRAME
    }
    return flags
}

// Keys are not always the type the constant implies — KEY_FRAME_RATE in particular comes back as a
// float from some extractors, and the typed getter throws rather than converting.
internal fun MediaFormat.getIntOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

internal fun MediaFormat.getLongOrNull(key: String): Long? =
        if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null
