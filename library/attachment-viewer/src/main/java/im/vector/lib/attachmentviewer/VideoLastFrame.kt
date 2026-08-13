/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.attachmentviewer

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build

object VideoLastFrame {

    /**
     * The presentation timestamp of a video's final frame, in ms, or -1.
     *
     * A video's reported duration usually sits past its last frame, so a frame-accurate seek to
     * the duration finds nothing to render. This walks the container's last GOP reading sample
     * metadata only — no decoding — to find the timestamp a seek can actually land on.
     */
    fun probeMs(context: Context, source: String): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) return -1
        val extractor = MediaExtractor()
        return runCatching {
            if (source.startsWith("content://")) {
                extractor.setDataSource(context, Uri.parse(source), null)
            } else {
                extractor.setDataSource(source)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return@runCatching -1
            extractor.selectTrack(track)
            extractor.seekTo(Long.MAX_VALUE, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            // Samples run in decode order, which with B-frames is not presentation order.
            var lastUs = -1L
            while (true) {
                val sampleUs = extractor.sampleTime
                if (sampleUs < 0) break
                if (sampleUs > lastUs) lastUs = sampleUs
                if (!extractor.advance()) break
            }
            if (lastUs < 0) -1 else (lastUs / 1000).toInt()
        }.getOrDefault(-1).also { runCatching { extractor.release() } }
    }
}
