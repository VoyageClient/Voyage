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
import kotlin.math.abs

/**
 * @property crop normalised rectangle (0..1) of the frame to keep, or null for the whole frame. It
 * is read in *display* space, i.e. after both the source orientation and [rotationDegrees].
 * @property rotationDegrees extra clockwise rotation applied on top of the source orientation.
 * @property muted drops the audio track entirely.
 * @property volume how much the audio is scaled by; 1 leaves it alone. Anything else rules out the
 * lossless audio copy, since the samples have to be decoded to be touched.
 * @property reversed plays the range backwards, which needs its own exporter: frames have to be
 * held decoded to be handed over in the other order.
 * @property targetWidth,targetHeight output size, or null to keep the cropped size. Resizing needs
 * the GL stage, so asking for one rules out the lossless remux exactly as a crop does.
 * @property targetBitrate output bitrate, or null to keep the source's (scaled down by how much of
 * the frame survives a crop or a resize).
 * @property speed how much faster the result runs; 1 leaves the timing alone.
 * @property changePitch whether the audio pitch rides along with [speed], as tape does.
 */
data class VideoEditSpec(
        val sourceUri: Uri,
        val startUs: Long,
        val endUs: Long,
        val crop: RectF?,
        val rotationDegrees: Int,
        val muted: Boolean,
        val outputFile: File,
        val targetWidth: Int? = null,
        val targetHeight: Int? = null,
        val targetBitrate: Int? = null,
        val speed: Float = 1f,
        val changePitch: Boolean = true,
        val volume: Float = 1f,
        val reversed: Boolean = false,
) {
    val isAmplified get() = abs(volume - 1f) > VOLUME_TOLERANCE

    /** Re-timing needs the GL stage too: frame timestamps can only be set from there. */
    val isRetimed get() = SpeedTimeMap.retimes(speed)

    /** Only the GL stage can change geometry, and only re-encoding can change the bitrate. */
    val needsTranscode get() = crop != null || targetWidth != null || targetBitrate != null || isRetimed

    companion object {
        private const val VOLUME_TOLERANCE = 0.001f
    }
}

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
