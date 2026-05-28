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

import android.media.MediaMetadataRetriever
import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.resize.AtMostResizer
import com.otaliastudios.transcoder.source.FilePathDataSource
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.listeners.ProgressListener
import org.matrix.android.sdk.internal.util.TemporaryFileCreator
import timber.log.Timber
import java.io.File
import javax.inject.Inject

internal class VideoCompressor @Inject constructor(
        private val temporaryFileCreator: TemporaryFileCreator
) {

    suspend fun compress(
            videoFile: File,
            progressListener: ProgressListener?
    ): VideoCompressionResult {
        // Cheap pre-check: if the source already fits the target envelope on resolution and
        // size, skip transcoding altogether. Transcoding is expensive, can take many seconds
        // and burns battery, so avoid running it on clips that are already chat-sized.
        if (isAlreadyWithinTargets(videoFile)) {
            Timber.d("Compressing: source already within targets, skipping transcode")
            return VideoCompressionResult.CompressionNotNeeded
        }

        val destinationFile = temporaryFileCreator.create()

        val job = Job()

        Timber.d("Compressing: start")
        progressListener?.onProgress(0, 100)

        // Explicit strategy: cap the longest side at 720 px so portrait + landscape sources
        // are both downsized while keeping their original aspect ratio. Without this, the
        // Transcoder default strategy can pick up an aspect resizer that produces square
        // output for portrait input. 2 Mbps + 30 fps is a reasonable trade-off for chat.
        val videoStrategy = DefaultVideoStrategy.Builder()
                .addResizer(AtMostResizer(720))
                .frameRate(30)
                .keyFrameInterval(3f)
                .bitRate(2_000_000L)
                .build()

        var result: Int = -1
        var failure: Throwable? = null
        Transcoder.into(destinationFile.path)
                .setVideoTrackStrategy(videoStrategy)
                .addDataSource(object : FilePathDataSource(videoFile.path) {
                    // https://github.com/natario1/Transcoder/issues/154
                    @Suppress("SENSELESS_COMPARISON") // Source is annotated as @NonNull, but can actually be null...
                    override fun isInitialized(): Boolean {
                        if (source == null) {
                            return false
                        }
                        return super.isInitialized()
                    }
                })
                .setListener(object : TranscoderListener {
                    override fun onTranscodeProgress(progress: Double) {
                        Timber.d("Compressing: $progress%")
                        progressListener?.onProgress((progress * 100).toInt(), 100)
                    }

                    override fun onTranscodeCompleted(successCode: Int) {
                        Timber.d("Compressing: success: $successCode")
                        result = successCode
                        job.complete()
                    }

                    override fun onTranscodeCanceled() {
                        Timber.d("Compressing: cancel")
                        job.cancel()
                    }

                    override fun onTranscodeFailed(exception: Throwable) {
                        Timber.w(exception, "Compressing: failure")
                        failure = exception
                        job.completeExceptionally(exception)
                    }
                })
                .transcode()

        job.join()

        // Note: job is also cancelled if completeExceptionally() was called
        if (job.isCancelled) {
            // Delete now the temporary file
            deleteFile(destinationFile)
            return when (val finalFailure = failure) {
                null -> {
                    // We do not throw a CancellationException, because it's not critical, we will try to send the original file
                    // Anyway this should never occurs, since we never cancel the return value of transcode()
                    Timber.w("Compressing: A failure occurred")
                    VideoCompressionResult.CompressionCancelled
                }
                else -> {
                    // Compression failure can also be considered as not critical, but let the caller decide
                    Timber.w("Compressing: Job cancelled")
                    VideoCompressionResult.CompressionFailed(finalFailure)
                }
            }
        }

        progressListener?.onProgress(100, 100)

        return when (result) {
            Transcoder.SUCCESS_TRANSCODED -> {
                VideoCompressionResult.Success(destinationFile)
            }
            Transcoder.SUCCESS_NOT_NEEDED -> {
                // Delete now the temporary file
                deleteFile(destinationFile)
                VideoCompressionResult.CompressionNotNeeded
            }
            else -> {
                // Should not happen...
                // Delete now the temporary file
                deleteFile(destinationFile)
                Timber.w("Unknown result: $result")
                VideoCompressionResult.CompressionFailed(IllegalStateException("Unknown result: $result"))
            }
        }
    }

    private suspend fun deleteFile(file: File) {
        withContext(Dispatchers.IO) {
            file.delete()
        }
    }

    private fun isAlreadyWithinTargets(videoFile: File): Boolean {
        // Bytes-based fast path: anything under the threshold is small enough that
        // transcoding it almost never saves meaningful bandwidth.
        if (videoFile.length() <= SKIP_TRANSCODE_BYTES) return true
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return false
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return false
            // Skip if both dimensions are within our cap AND the file is reasonably small
            // for the duration. The Transcoder strategy targets ~2 Mbps so anything already
            // below that with a small longest-side is a waste of CPU to re-encode.
            val longestSide = maxOf(width, height)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val estimatedBitrate = if (durationMs > 0) (videoFile.length() * 8_000 / durationMs) else Long.MAX_VALUE
            longestSide <= TARGET_LONGEST_SIDE && estimatedBitrate <= TARGET_BITRATE
        } catch (e: Exception) {
            Timber.w(e, "Compressing: failed to inspect source, will transcode")
            false
        } finally {
            retriever.release()
        }
    }

    companion object {
        private const val TARGET_LONGEST_SIDE = 720
        private const val TARGET_BITRATE = 2_000_000L
        // 2 MB under-threshold pass-through. Below this, transcoding rarely pays for itself.
        private const val SKIP_TRANSCODE_BYTES = 2L * 1024 * 1024
    }
}
