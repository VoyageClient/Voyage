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

    private const val DEFAULT_FRAME_RATE = 30

    /**
     * Roughly where a video's final frame sits, in ms, or -1.
     *
     * A video's duration usually sits past its last frame, so a frame-accurate seek to the duration
     * finds nothing to render; one frame back from the video track's own duration always lands on a
     * real one.
     *
     * Read from the track header alone. Walking the samples would give the exact timestamp, but
     * reading a file's last GOP trips an overflow bug in the platform's MP4 extractor
     * (MPEG4Source::read) on some files, and that aborts the shared media.extractor process —
     * killing whatever is playing at the time along with it.
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
            val format = (0 until extractor.trackCount)
                    .map { extractor.getTrackFormat(it) }
                    .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                    ?: return@runCatching -1
            val durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
            if (durationUs <= 0L) return@runCatching -1
            val frameRate = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
                    ?: runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE).toInt() }.getOrNull()
            val frameIntervalUs = 1_000_000L / (frameRate?.takeIf { it > 0 } ?: DEFAULT_FRAME_RATE)
            ((durationUs - frameIntervalUs).coerceAtLeast(0L) / 1000).toInt()
        }.getOrDefault(-1).also { runCatching { extractor.release() } }
    }
}
