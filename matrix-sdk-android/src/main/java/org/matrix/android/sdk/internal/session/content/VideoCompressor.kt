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
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.listeners.ProgressListener
import org.matrix.android.sdk.internal.util.TemporaryFileCreator
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject

/**
 * Re-encodes a video to H.264 at a reduced bitrate using the platform [MediaCodec] /
 * [MediaMuxer] stack (works down to KitKat, no native deps). Video is transcoded surface-to-surface
 * (decoder output Surface == encoder input Surface) so we never touch pixels on the CPU; audio is
 * copied through untouched. Every blocking codec call uses a timeout plus an overall stall watchdog
 * so a wedged codec falls back to "keep the original" rather than hanging the upload.
 */
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

        val result = withContext(Dispatchers.Default) {
            runCatching {
                transcode(sourceUri, destinationFile, progressListener) { isActive }
            }
        }

        result.fold(
                onSuccess = { completed ->
                    if (!completed) {
                        deleteFile(destinationFile)
                        return@coroutineScope VideoCompressionResult.CompressionFailed(
                                IllegalStateException("Transcoder stalled or was cancelled")
                        )
                    }
                },
                onFailure = { t ->
                    Timber.w(t, "Compressing: transcode failed")
                    deleteFile(destinationFile)
                    return@coroutineScope VideoCompressionResult.CompressionFailed(t)
                }
        )

        progressListener?.onProgress(100, 100)
        // Safety net: re-encoding can produce a larger file than the source (already-efficient
        // inputs). If that happens, discard the re-encode and keep the original.
        if (sourceSize > 0 && destinationFile.length() >= sourceSize) {
            Timber.d("Compressing: result ${destinationFile.length()} >= source $sourceSize, keeping original")
            deleteFile(destinationFile)
            return@coroutineScope VideoCompressionResult.CompressionNotNeeded
        }
        VideoCompressionResult.Success(destinationFile)
    }

    /**
     * @return true if the transcode ran to completion, false if it was abandoned (stall/cancel).
     */
    private fun transcode(
            sourceUri: Uri,
            destinationFile: File,
            progressListener: ProgressListener?,
            isActive: () -> Boolean,
    ): Boolean {
        val videoExtractor = MediaExtractor()
        var audioExtractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var inputSurface: android.view.Surface? = null
        var muxer: MediaMuxer? = null

        try {
            videoExtractor.setDataSource(context, sourceUri, null)
            val videoTrackIndex = videoExtractor.firstTrackOf("video/") ?: return false
            videoExtractor.selectTrack(videoTrackIndex)
            val sourceFormat = videoExtractor.getTrackFormat(videoTrackIndex)

            val width = sourceFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = sourceFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val durationUs = if (sourceFormat.containsKey(MediaFormat.KEY_DURATION)) sourceFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val frameRate = if (sourceFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) sourceFormat.getInteger(MediaFormat.KEY_FRAME_RATE) else DEFAULT_FRAME_RATE

            // Encode at the source resolution (changing resolution would need a GL pipeline); the
            // win comes from capping the bitrate. Never raise it above the target.
            val targetFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, TARGET_BITRATE.toInt())
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(targetFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            inputSurface = encoder.createInputSurface()
            encoder.start()

            decoder = MediaCodec.createDecoderByType(sourceFormat.getString(MediaFormat.KEY_MIME)!!).apply {
                configure(sourceFormat, inputSurface, null, 0)
                start()
            }

            muxer = MediaMuxer(destinationFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            readRotation(sourceUri)?.let { muxer.setOrientationHint(it) }

            // Optional audio passthrough (no re-encode).
            val audioCopier = AudioPassthrough.create(context, sourceUri, muxer)
            audioExtractor = audioCopier?.extractor

            val completed = runVideoLoop(
                    videoExtractor, decoder, encoder, muxer, audioCopier, durationUs, progressListener, isActive
            )
            if (completed) audioCopier?.copyAll(isActive)
            return completed
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { inputSurface?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor?.release() }
        }
    }

    // The indexed getInputBuffer/getOutputBuffer accessors are API 21+; KitKat needs the legacy
    // ByteBuffer[] arrays, so we deliberately use the deprecated API here.
    @Suppress("LongParameterList", "DEPRECATION")
    private fun runVideoLoop(
            extractor: MediaExtractor,
            decoder: MediaCodec,
            encoder: MediaCodec,
            muxer: MediaMuxer,
            audioCopier: AudioPassthrough?,
            durationUs: Long,
            progressListener: ProgressListener?,
            isActive: () -> Boolean,
    ): Boolean {
        val bufferInfo = MediaCodec.BufferInfo()
        var decoderInputBuffers = decoder.inputBuffers
        var encoderOutputBuffers = encoder.outputBuffers
        var muxerStarted = false
        var outVideoTrack = -1
        var inputDone = false
        var decoderDone = false
        var encoderDone = false
        var lastProgressAt = SystemClock.elapsedRealtime()
        var lastReportedProgress = -1

        while (!encoderDone) {
            if (!isActive()) return false
            if (SystemClock.elapsedRealtime() - lastProgressAt > STALL_TIMEOUT_MS) {
                Timber.w("Compressing: no progress for >${STALL_TIMEOUT_MS}ms, abandoning transcode")
                return false
            }

            // 1) Feed the decoder from the extractor.
            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val inBuf = decoderInputBuffers[inIndex].apply { clear() }
                    val sampleSize = extractor.readSampleData(inBuf, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                        lastProgressAt = SystemClock.elapsedRealtime()
                    }
                }
            }

            // 2) Drain the decoder onto the encoder input surface.
            if (!decoderDone) {
                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIndex >= 0) {
                    val render = bufferInfo.size > 0
                    decoder.releaseOutputBuffer(outIndex, render)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        decoderDone = true
                        encoder.signalEndOfInputStream()
                    }
                }
            }

            // 3) Drain the encoder to the muxer.
            val encIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                encIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                    encoderOutputBuffers = encoder.outputBuffers
                }
                encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outVideoTrack = muxer.addTrack(encoder.outputFormat)
                    audioCopier?.addTrack(muxer)
                    muxer.start()
                    muxerStarted = true
                }
                encIndex >= 0 -> {
                    val encoded = encoderOutputBuffers[encIndex]
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(outVideoTrack, encoded, bufferInfo)
                        lastProgressAt = SystemClock.elapsedRealtime()
                        if (durationUs > 0) {
                            val progress = (bufferInfo.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 99)
                            if (progress != lastReportedProgress) {
                                lastReportedProgress = progress
                                progressListener?.onProgress(progress, 100)
                            }
                        }
                    }
                    encoder.releaseOutputBuffer(encIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encoderDone = true
                    }
                }
            }
        }
        return muxerStarted
    }

    /** Copies a single audio track through to the muxer without re-encoding. */
    private class AudioPassthrough private constructor(
            val extractor: MediaExtractor,
            private val format: MediaFormat,
            private val muxer: MediaMuxer,
    ) {
        private var track = -1

        fun addTrack(muxer: MediaMuxer) {
            track = muxer.addTrack(format)
        }

        fun copyAll(isActive: () -> Boolean) {
            if (track < 0) return
            val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                DEFAULT_AUDIO_BUFFER
            }
            val buffer = ByteBuffer.allocate(maxSize)
            val info = MediaCodec.BufferInfo()
            while (isActive()) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlagsCompat()
                muxer.writeSampleData(track, buffer, info)
                extractor.advance()
            }
        }

        companion object {
            fun create(context: Context, sourceUri: Uri, muxer: MediaMuxer): AudioPassthrough? {
                val extractor = MediaExtractor()
                return try {
                    extractor.setDataSource(context, sourceUri, null)
                    val index = extractor.firstTrackOf("audio/")
                    if (index == null) {
                        extractor.release()
                        null
                    } else {
                        extractor.selectTrack(index)
                        AudioPassthrough(extractor, extractor.getTrackFormat(index), muxer)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Compressing: no audio track to copy")
                    runCatching { extractor.release() }
                    null
                }
            }
        }
    }

    private fun readRotation(sourceUri: Uri): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, sourceUri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
        } catch (e: Exception) {
            null
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
        private const val DEFAULT_FRAME_RATE = 30
        private const val I_FRAME_INTERVAL_SECONDS = 1
        private const val TIMEOUT_US = 10_000L
        private const val STALL_TIMEOUT_MS = 15_000L
        private const val DEFAULT_AUDIO_BUFFER = 256 * 1024
    }
}

private fun MediaExtractor.firstTrackOf(mimePrefix: String): Int? {
    for (i in 0 until trackCount) {
        val mime = getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
        if (mime.startsWith(mimePrefix)) return i
    }
    return null
}

private fun MediaExtractor.sampleFlagsCompat(): Int {
    var flags = 0
    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
        @Suppress("DEPRECATION")
        flags = flags or MediaCodec.BUFFER_FLAG_SYNC_FRAME
    }
    return flags
}
