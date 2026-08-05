/*
 * Copyright 2021 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.content

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import im.vector.lib.mediatranscode.MediaSourceInfo
import im.vector.lib.mediatranscode.VideoEditExporter
import im.vector.lib.mediatranscode.VideoEditProgressListener
import im.vector.lib.mediatranscode.VideoEditSpec
import kotlinx.coroutines.coroutineScope
import org.matrix.android.sdk.api.listeners.ProgressListener
import org.matrix.android.sdk.internal.util.TemporaryFileCreator
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Re-encodes a video to H.264 at a reduced bitrate, and optionally at a smaller frame, through
 * `:library:media-transcode` — the same pipeline the attachment editor exports with, so a fix to
 * one is a fix to both. MediaMuxer is API 18+, so below that the original is uploaded unchanged:
 * API 14-15 have no MediaCodec at all and a software transcode is impractically slow on that
 * hardware, so uncompressed upload is the deliberate behaviour there.
 */
@SuppressLint("NewApi")
internal class VideoCompressor @Inject constructor(
        private val context: Context,
        private val temporaryFileCreator: TemporaryFileCreator,
) {

    /**
     * @param targetWidth,targetHeight an explicit output size chosen by the sender, or null to
     * bound the shortest side at [TARGET_SHORTEST_SIDE].
     * @param targetBitrate an explicit bitrate chosen by the sender, or null for [TARGET_BITRATE].
     */
    suspend fun compress(
            sourceUri: Uri,
            sourceSize: Long,
            targetWidth: Int? = null,
            targetHeight: Int? = null,
            targetBitrate: Int? = null,
            progressListener: ProgressListener?,
    ): VideoCompressionResult = coroutineScope {
        if (!VideoEditExporter.isSupported()) {
            return@coroutineScope VideoCompressionResult.CompressionNotNeeded
        }
        // An explicit request is always honoured; only the automatic pass may decide to skip.
        val chosenBySender = targetWidth != null || targetHeight != null || targetBitrate != null
        if (!chosenBySender && isAlreadyWithinTargets(sourceUri, sourceSize)) {
            Timber.d("Compressing: source already within targets, skipping transcode")
            return@coroutineScope VideoCompressionResult.CompressionNotNeeded
        }

        val source = MediaSourceInfo.probe(context, sourceUri)
        if (source == null || source.durationUs <= 0) {
            Timber.w("Compressing: cannot probe $sourceUri")
            return@coroutineScope VideoCompressionResult.CompressionNotNeeded
        }
        val size = requestedSize(source, targetWidth, targetHeight) ?: boundedSize(source)
        val destinationFile = temporaryFileCreator.create()
        progressListener?.onProgress(0, 100)

        val result = runCatching {
            VideoEditExporter.export(
                    context,
                    VideoEditSpec(
                            sourceUri = sourceUri,
                            startUs = 0,
                            endUs = source.durationUs,
                            crop = null,
                            rotationDegrees = 0,
                            muted = false,
                            outputFile = destinationFile,
                            targetWidth = size.first,
                            targetHeight = size.second,
                            targetBitrate = targetBitrate ?: TARGET_BITRATE,
                    ),
                    VideoEditProgressListener { percent -> progressListener?.onProgress(percent, 100) }
            )
        }

        result.fold(
                onSuccess = { progressListener?.onProgress(100, 100) },
                onFailure = { t ->
                    // The exporter already removed its own partial file.
                    if (t is InterruptedException) return@coroutineScope VideoCompressionResult.CompressionCancelled
                    Timber.w(t, "Compressing: transcode failed")
                    return@coroutineScope VideoCompressionResult.CompressionFailed(t)
                }
        )

        // Re-encoding can produce a larger file than the source (already-efficient inputs). Keep
        // the original then — unless the sender asked for a specific size, which they still get.
        if (!chosenBySender && sourceSize > 0 && destinationFile.length() >= sourceSize) {
            Timber.d("Compressing: result ${destinationFile.length()} >= source $sourceSize, keeping original")
            deleteFile(destinationFile)
            return@coroutineScope VideoCompressionResult.CompressionNotNeeded
        }
        VideoCompressionResult.Success(destinationFile)
    }

    /** The size the sender asked for, with a missing axis taken from the source's shape. */
    private fun requestedSize(source: MediaSourceInfo, width: Int?, height: Int?): Pair<Int, Int>? {
        val aspect = source.displayWidth.toFloat() / source.displayHeight.coerceAtLeast(1)
        return when {
            width != null && height != null -> width to height
            width != null -> width to (width / aspect).roundToInt().coerceAtLeast(1)
            height != null -> (height * aspect).roundToInt().coerceAtLeast(1) to height
            else -> null
        }
    }

    private fun boundedSize(source: MediaSourceInfo): Pair<Int, Int> {
        val width = source.displayWidth
        val height = source.displayHeight
        val shortest = min(width, height)
        if (shortest <= TARGET_SHORTEST_SIDE) return width to height
        val scale = TARGET_SHORTEST_SIDE.toFloat() / shortest
        return (width * scale).roundToInt() to (height * scale).roundToInt()
    }

    private fun isAlreadyWithinTargets(sourceUri: Uri, sourceSize: Long): Boolean {
        if (sourceSize in 1..SKIP_TRANSCODE_BYTES) return true
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, sourceUri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return false
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return false
            val shortestSide = minOf(width, height)
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: return false
            shortestSide <= TARGET_SHORTEST_SIDE && bitrate <= TARGET_BITRATE
        } catch (t: Throwable) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun deleteFile(file: File) {
        runCatching { file.delete() }
    }

    companion object {
        private const val TARGET_SHORTEST_SIDE = 720
        private const val TARGET_BITRATE = 2_000_000
        private const val SKIP_TRANSCODE_BYTES = 4L * 1024 * 1024
    }
}
