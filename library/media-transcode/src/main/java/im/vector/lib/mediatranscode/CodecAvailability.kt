/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode

import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.annotation.RequiresApi
import timber.log.Timber

internal object CodecAvailability {

    /** The MediaCodecList(REGULAR_CODECS) constructor is API 21+, so enumerate the legacy way. */
    @RequiresApi(16)
    @Suppress("DEPRECATION")
    fun hasAvcEncoder(): Boolean {
        return try {
            (0 until MediaCodecList.getCodecCount())
                    .map { MediaCodecList.getCodecInfoAt(it) }
                    .any { info ->
                        info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
                    }
        } catch (e: Exception) {
            Timber.w(e, "VideoEdit: could not enumerate codecs")
            false
        }
    }
}
