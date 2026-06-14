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

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.listeners.ProgressListener
import org.matrix.android.sdk.internal.util.TemporaryFileCreator
import timber.log.Timber
import java.io.File
import javax.inject.Inject

@OptIn(UnstableApi::class)
internal class VideoCompressor @Inject constructor(
        private val context: Context,
        private val temporaryFileCreator: TemporaryFileCreator,
) {

    suspend fun compress(
            sourceUri: Uri,
            sourceSize: Long,
            progressListener: ProgressListener?,
    ): VideoCompressionResult = coroutineScope {
        if (isAlreadyWithinTargets(sourceUri, sourceSize)) {
            Timber.d("Compressing: source already within targets, skipping transcode")
            return@coroutineScope VideoCompressionResult.CompressionNotNeeded
        }

        val destinationFile = temporaryFileCreator.create()
        progressListener?.onProgress(0, 100)

        var stalled = false
        var failure: Throwable? = null

        try {
            // Transformer must be created and driven from a thread with a Looper. The actual
            // encode/decode happens on its own internal pool, so this doesn't burn the UI thread.
            withContext(Dispatchers.Main) {
                val done = CompletableDeferred<Unit>()
                val transformer = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(
                                DefaultEncoderFactory.Builder(context)
                                        .setRequestedVideoEncoderSettings(
                                                VideoEncoderSettings.Builder()
                                                        .setBitrate(TARGET_BITRATE.toInt())
                                                        // Main profile, level 3.1 fits 720p@30 and compresses better
                                                        // than baseline. High would be smaller still but is much
                                                        // slower to encode on mid-range hardware.
                                                        .setEncodingProfileLevel(
                                                                CodecProfileLevel.AVCProfileMain,
                                                                CodecProfileLevel.AVCLevel31,
                                                        )
                                                        .build()
                                        )
                                        .setEnableFallback(true)
                                        .build()
                        )
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                done.complete(Unit)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                failure = exportException
                                done.completeExceptionally(exportException)
                            }
                        })
                        .build()

                val videoEffects = buildList {
                    val (srcW, srcH) = readVideoDimensions(sourceUri)
                    if (srcW > 0 && srcH > 0) {
                        // Scale proportionally so the *shortest* side <= TARGET_SHORTEST_SIDE,
                        // letting the longer side scale freely. This keeps long/wide videos from
                        // being squished (e.g. 155x720); their shorter side stays at the target (or
                        // its original value if already below it). Capped at 1 so we never upscale.
                        val scale = minOf(TARGET_SHORTEST_SIDE.toFloat() / minOf(srcW, srcH), 1f)
                        if (scale < 1f) {
                            add(ScaleAndRotateTransformation.Builder().setScale(scale, scale).build())
                        }
                    } else {
                        // Couldn't read dimensions; fall back to bounded canvas which letterboxes
                        // but is at least correct.
                        add(Presentation.createForWidthAndHeight(TARGET_SHORTEST_SIDE, TARGET_SHORTEST_SIDE, Presentation.LAYOUT_SCALE_TO_FIT))
                    }
                }
                val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(sourceUri))
                        .setEffects(Effects(emptyList(), videoEffects))
                        .build()

                transformer.start(editedMediaItem, destinationFile.absolutePath)

                // Watchdog: poll progress and bail if it doesn't advance. Media3 reports real
                // numeric progress (PROGRESS_STATE_AVAILABLE) once the codec is producing
                // output; if it stays not-started past the timeout we treat the codec as hung
                // and abandon so the worker can fall back to uploading the original.
                val watchdog = launch {
                    val holder = ProgressHolder()
                    var lastProgress = -1
                    var lastAdvanceAt = SystemClock.elapsedRealtime()
                    while (isActive && !done.isCompleted) {
                        delay(WATCHDOG_INTERVAL_MS)
                        val state = transformer.getProgress(holder)
                        val current = if (state == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else -1
                        if (current >= 0 && current != lastProgress) {
                            lastProgress = current
                            lastAdvanceAt = SystemClock.elapsedRealtime()
                            progressListener?.onProgress(current, 100)
                        } else if (SystemClock.elapsedRealtime() - lastAdvanceAt > STALL_TIMEOUT_MS) {
                            Timber.w("Compressing: stalled for >${STALL_TIMEOUT_MS}ms, abandoning transcode")
                            stalled = true
                            runCatching { transformer.cancel() }
                            done.completeExceptionally(IllegalStateException("Transformer stalled"))
                            break
                        }
                    }
                }

                try {
                    done.await()
                } finally {
                    watchdog.cancel()
                    if (!done.isCompleted) runCatching { transformer.cancel() }
                }
            }
        } catch (t: Throwable) {
            deleteFile(destinationFile)
            // Re-raise real parent-coroutine cancellation; only swallow CancellationException
            // when it came from our own watchdog/transformer error path.
            if (t is CancellationException && !stalled && failure == null && isActive) throw t
            return@coroutineScope when {
                stalled -> VideoCompressionResult.CompressionFailed(failure ?: IllegalStateException("Transformer stalled"))
                failure != null -> VideoCompressionResult.CompressionFailed(failure!!)
                else -> VideoCompressionResult.CompressionCancelled
            }
        }

        progressListener?.onProgress(100, 100)
        // Safety net: encoder + container overhead can make the result larger than the source
        // (already-efficient inputs, audio re-encode, etc.). If that happens, discard the
        // re-encode and tell the caller to keep the original.
        if (sourceSize > 0 && destinationFile.length() >= sourceSize) {
            Timber.d("Compressing: result ${destinationFile.length()} >= source $sourceSize, keeping original")
            deleteFile(destinationFile)
            return@coroutineScope VideoCompressionResult.CompressionNotNeeded
        }
        VideoCompressionResult.Success(destinationFile)
    }

    private fun readVideoDimensions(sourceUri: Uri): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, sourceUri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) h to w else w to h
        } catch (e: Exception) {
            0 to 0
        } finally {
            retriever.release()
        }
    }

    private suspend fun deleteFile(file: File) {
        withContext(Dispatchers.IO) {
            file.delete()
        }
    }

    private fun isAlreadyWithinTargets(sourceUri: Uri, sourceSize: Long): Boolean {
        if (sourceSize in 1..SKIP_TRANSCODE_BYTES) return true
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, sourceUri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return false
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return false
            val shortestSide = minOf(width, height)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val estimatedBitrate = if (durationMs > 0 && sourceSize > 0) (sourceSize * 8_000 / durationMs) else Long.MAX_VALUE
            shortestSide <= TARGET_SHORTEST_SIDE && estimatedBitrate <= TARGET_BITRATE
        } catch (e: Exception) {
            Timber.w(e, "Compressing: failed to inspect source, will transcode")
            false
        } finally {
            retriever.release()
        }
    }

    companion object {
        private const val TARGET_SHORTEST_SIDE = 720
        private const val TARGET_BITRATE = 2_000_000L
        private const val SKIP_TRANSCODE_BYTES = 4L * 1024 * 1024
        private const val WATCHDOG_INTERVAL_MS = 500L
        private const val STALL_TIMEOUT_MS = 15_000L
    }
}
