/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import androidx.annotation.RequiresApi
import timber.log.Timber

/**
 * An mp4 can only start at a sync frame, so a lossless trim really begins at the sync frame at or
 * before the requested start. The UI snaps its handle to this so the preview matches the export.
 */
@RequiresApi(16)
object SyncFrameLocator {

    fun previousSyncUs(context: Context, uri: Uri, timeUs: Long): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val track = extractor.firstTrackOf("video/") ?: return timeUs
            extractor.selectTrack(track)
            extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            extractor.sampleTime.takeIf { it >= 0 } ?: 0L
        } catch (e: Exception) {
            Timber.w(e, "VideoEdit: sync frame lookup failed")
            timeUs
        } finally {
            runCatching { extractor.release() }
        }
    }
}
