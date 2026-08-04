/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode

import android.graphics.RectF
import android.net.Uri
import java.io.File

/**
 * @property crop normalised rectangle (0..1) of the frame to keep, or null for the whole frame. It
 * is read in *display* space, i.e. after both the source orientation and [rotationDegrees].
 * @property rotationDegrees extra clockwise rotation applied on top of the source orientation.
 * @property muted drops the audio track entirely.
 */
data class VideoEditSpec(
        val sourceUri: Uri,
        val startUs: Long,
        val endUs: Long,
        val crop: RectF?,
        val rotationDegrees: Int,
        val muted: Boolean,
        val outputFile: File,
)

data class VideoEditOutput(
        val file: File,
        val width: Int,
        val height: Int,
        val durationMs: Long,
        /**
         * Where the output really starts. The lossless path can only begin at a sync frame, so this
         * may precede [VideoEditSpec.startUs].
         */
        val actualStartUs: Long,
        val audioDropped: Boolean,
)

fun interface VideoEditProgressListener {
    fun onProgress(percent: Int)
}

sealed class VideoEditException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NoVideoTrack : VideoEditException("Source has no video track")
    class UnsupportedCodec(val mime: String) : VideoEditException("Codec $mime cannot be copied into an mp4")
    class NotEnoughSpace(val requiredBytes: Long) : VideoEditException("Not enough free space: need $requiredBytes bytes")
    class Stalled : VideoEditException("Export made no progress")
    class MuxerRejected(cause: Throwable) : VideoEditException("Muxer rejected the source format", cause)
    class EmptyRange : VideoEditException("Selected range contains no frames")
}
