/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode

import android.os.Build

/** What [android.media.MediaMuxer]'s mp4 container will accept, per platform level. */
internal object MuxableFormats {

    fun isMuxableVideo(mime: String): Boolean = when (mime) {
        "video/avc", "video/mp4v-es", "video/3gpp" -> true
        "video/hevc" -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        else -> false
    }

    fun isMuxableAudio(mime: String): Boolean = when (mime) {
        "audio/mp4a-latm", "audio/3gpp", "audio/amr-wb" -> true
        "audio/mpeg" -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
        else -> false
    }
}
