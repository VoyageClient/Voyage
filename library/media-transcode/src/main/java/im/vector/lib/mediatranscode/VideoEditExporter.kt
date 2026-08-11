/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Applies a [VideoEditSpec] to produce an mp4. Editing needs MediaMuxer, and the GL stage needs
 * EGLExt to stamp frame timestamps, so it is unavailable below API 18 — see [isSupported].
 */
object VideoEditExporter {

    fun isSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2

    @RequiresApi(18)
    suspend fun export(
            context: Context,
            spec: VideoEditSpec,
            progressListener: VideoEditProgressListener? = null,
    ): VideoEditOutput = coroutineScope {
        progressListener?.onProgress(0)
        // Probing opens an extractor and a retriever, so it belongs off the caller's thread along
        // with the export itself. Deleting inside this context too, so a partial file is still
        // cleaned up when the caller cancels — the withContext boundary would throw before any
        // code out here could run.
        val result = withContext(Dispatchers.Default) {
            runCatching {
                val source = MediaSourceInfo.probe(context, spec.sourceUri) ?: throw VideoEditException.NoVideoTrack()
                ensureFreeSpace(spec, source)
                Timber.d(
                        "VideoEdit: source ${source.videoMime} + ${source.audioMime ?: "no audio"}, " +
                                "${source.width}x${source.height} @${source.rotationDegrees}°, speed ${spec.speed}, muted ${spec.muted}"
                )
                if (canRemuxLosslessly(context, spec, source)) {
                    LosslessTrimExporter(context).export(spec, source, progressListener) { isActive }
                } else {
                    TranscodeExporter(context).export(spec, source, progressListener) { isActive }
                }
            }.onFailure { t ->
                Timber.w(t, "VideoEdit: export failed")
                spec.outputFile.delete()
            }
        }
        result.getOrThrow().also { progressListener?.onProgress(100) }
    }

    /**
     * A remux can only begin at a sync frame, so it is used when the requested start already is one
     * (an end-only trim always is). Anywhere else it would silently hand back extra leading video,
     * so the re-encoding path takes over — unless there is no encoder to do it with.
     */
    @RequiresApi(18)
    private fun canRemuxLosslessly(context: Context, spec: VideoEditSpec, source: MediaSourceInfo): Boolean {
        if (spec.needsTranscode) return false
        // A codec an mp4 cannot hold has to be re-encoded whatever the trim looks like.
        if (!MuxableFormats.isMuxableVideo(source.videoMime)) return false
        val syncUs = SyncFrameLocator.previousSyncUs(context, spec.sourceUri, spec.startUs)
        if (spec.startUs - syncUs <= SYNC_FRAME_TOLERANCE_US) return true
        if (!CodecAvailability.hasAvcEncoder()) {
            Timber.w("VideoEdit: no AVC encoder, trimming from the sync frame at ${syncUs}us instead")
            return true
        }
        return false
    }

    private fun ensureFreeSpace(spec: VideoEditSpec, source: MediaSourceInfo) {
        if (source.bitrate <= 0) return
        val rangeSeconds = (spec.endUs - spec.startUs).coerceAtLeast(0) / 1_000_000.0
        val required = (source.bitrate / 8 * rangeSeconds * SPACE_HEADROOM).toLong()
        val available = spec.outputFile.parentFile?.usableSpace ?: return
        if (available < required) throw VideoEditException.NotEnoughSpace(required)
    }

    private const val SPACE_HEADROOM = 1.3

    /** Under this much extra leading video, remuxing is imperceptible and much faster. */
    private const val SYNC_FRAME_TOLERANCE_US = 100_000L
}
