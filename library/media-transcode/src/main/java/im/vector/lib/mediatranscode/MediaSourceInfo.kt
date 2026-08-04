/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.RequiresApi
import timber.log.Timber

data class MediaSourceInfo(
        val videoMime: String,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val durationUs: Long,
        val audioMime: String?,
        val bitrate: Int,
        val frameRate: Float,
) {
    /** Dimensions as displayed, i.e. with the orientation hint applied. */
    val displayWidth: Int get() = if (rotationDegrees % 180 == 90) height else width
    val displayHeight: Int get() = if (rotationDegrees % 180 == 90) width else height

    companion object {

        @RequiresApi(16)
        fun probe(context: Context, uri: Uri): MediaSourceInfo? {
            val extractor = MediaExtractor()
            return try {
                extractor.setDataSource(context, uri, null)
                val videoTrack = extractor.firstTrackOf("video/") ?: return null
                val videoFormat = extractor.getTrackFormat(videoTrack)
                val audioMime = extractor.firstTrackOf("audio/")
                        ?.let { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
                val metadata = readMetadata(context, uri)
                MediaSourceInfo(
                        videoMime = videoFormat.getString(MediaFormat.KEY_MIME) ?: return null,
                        width = videoFormat.getIntOrNull(MediaFormat.KEY_WIDTH) ?: return null,
                        height = videoFormat.getIntOrNull(MediaFormat.KEY_HEIGHT) ?: return null,
                        rotationDegrees = metadata.rotationDegrees,
                        durationUs = videoFormat.getLongOrNull(MediaFormat.KEY_DURATION)
                                ?: metadata.durationUs,
                        audioMime = audioMime,
                        bitrate = metadata.bitrate,
                        frameRate = videoFormat.getIntOrNull(MediaFormat.KEY_FRAME_RATE)?.toFloat()
                                ?: DEFAULT_FRAME_RATE,
                )
            } catch (e: Exception) {
                Timber.w(e, "VideoEdit: failed to probe $uri")
                null
            } finally {
                runCatching { extractor.release() }
            }
        }

        private data class Metadata(val rotationDegrees: Int, val bitrate: Int, val durationUs: Long)

        /** Rotation and bitrate are not exposed by MediaExtractor, and KEY_DURATION can be absent. */
        private fun readMetadata(context: Context, uri: Uri): Metadata {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context, uri)
                Metadata(
                        rotationDegrees = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                        bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0,
                        durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000,
                )
            } catch (e: Exception) {
                Metadata(0, 0, 0)
            } finally {
                runCatching { retriever.release() }
            }
        }

        private const val DEFAULT_FRAME_RATE = 30f
    }
}
