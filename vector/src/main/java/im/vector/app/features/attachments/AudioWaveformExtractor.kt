/*
 * Copyright 2024 The Matrix.org Foundation C.I.C.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import timber.log.Timber
import kotlin.math.abs

object AudioWaveformExtractor {

    private const val TARGET_SAMPLE_COUNT = 50
    private const val CODEC_TIMEOUT_US = 10_000L

    fun extract(context: Context, uri: Uri): List<Int> {
        val extractor = MediaExtractor()
        val raw = mutableListOf<Int>()
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                extractor.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            } ?: extractor.setDataSource(context, uri, null)

            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return emptyList()
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return emptyList()

            val sampleRate = format.takeIfHas(MediaFormat.KEY_SAMPLE_RATE)?.getInteger(MediaFormat.KEY_SAMPLE_RATE) ?: 44100
            val channels = format.takeIfHas(MediaFormat.KEY_CHANNEL_COUNT)?.getInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
            val durationUs = format.takeIfHas(MediaFormat.KEY_DURATION)?.getLong(MediaFormat.KEY_DURATION) ?: 0L
            val totalPcmFrames = (durationUs.coerceAtLeast(1L) * sampleRate / 1_000_000).coerceAtLeast(1L)
            // Collect ~4x the target so we have headroom for the downsample step.
            val rawTarget = TARGET_SAMPLE_COUNT * 4
            val framesPerBucket = (totalPcmFrames / rawTarget).coerceAtLeast(1L).toInt()

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var bucketFramesRead = 0
            var bucketPeak = 0

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val buffer = codec.getInputBuffer(inputIndex) ?: continue
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                if (outputIndex >= 0) {
                    val output = codec.getOutputBuffer(outputIndex)
                    if (output != null && info.size > 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        val shorts = output.asShortBuffer()
                        while (shorts.remaining() > 0) {
                            // PCM frame = `channels` samples; we take max across channels per frame.
                            var frameMax = 0
                            var c = 0
                            while (c < channels && shorts.remaining() > 0) {
                                val s = abs(shorts.get().toInt())
                                if (s > frameMax) frameMax = s
                                c++
                            }
                            if (frameMax > bucketPeak) bucketPeak = frameMax
                            bucketFramesRead++
                            if (bucketFramesRead >= framesPerBucket) {
                                raw.add(bucketPeak)
                                bucketPeak = 0
                                bucketFramesRead = 0
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            if (bucketFramesRead > 0) raw.add(bucketPeak)

            codec.stop()
            codec.release()

            downsample(raw, TARGET_SAMPLE_COUNT)
        } catch (t: Throwable) {
            Timber.w(t, "Failed to extract waveform from audio file")
            emptyList()
        } finally {
            extractor.release()
        }
    }

    private fun downsample(values: List<Int>, target: Int): List<Int> {
        if (values.size <= target) return values
        val chunkSize = values.size / target
        return values.chunked(chunkSize) { it.maxOrNull() ?: 0 }
    }

    private fun MediaFormat.takeIfHas(key: String): MediaFormat? = if (containsKey(key)) this else null
}
