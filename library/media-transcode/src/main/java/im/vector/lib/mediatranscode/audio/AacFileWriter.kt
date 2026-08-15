/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.annotation.RequiresApi
import java.io.File
import java.nio.ByteOrder

/** Encodes PCM to AAC and writes it out as an mp4 with nothing but that one track. */
@RequiresApi(18)
@Suppress("DEPRECATION")
internal class AacFileWriter(
        private val sampleRate: Int,
        private val channelCount: Int,
        outputFile: File,
) {

    private val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val encoder: MediaCodec
    private var inputBuffers: Array<java.nio.ByteBuffer>
    private var outputBuffers: Array<java.nio.ByteBuffer>
    private val info = MediaCodec.BufferInfo()

    private var track = -1
    private var started = false
    private var finished = false

    /** How much sound has gone in, which is also the clock the output is stamped with. */
    var emittedFrames = 0L
        private set

    val durationUs get() = if (sampleRate == 0) 0L else emittedFrames * 1_000_000L / sampleRate

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (channelCount > 1) STEREO_BIT_RATE else MONO_BIT_RATE)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        inputBuffers = encoder.inputBuffers
        outputBuffers = encoder.outputBuffers
    }

    fun write(samples: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            // Draining first: the encoder only frees an input buffer once its output has been
            // taken, so waiting on one without draining is most of an export spent asleep.
            drain(false)
            val index = dequeueInput() ?: continue
            val buffer = inputBuffers[index].apply {
                clear()
                order(ByteOrder.nativeOrder())
            }
            val shorts = minOf(buffer.capacity() / 2, count - offset)
            // A partial frame in an encoder buffer offsets every channel after it.
            val aligned = shorts - shorts % channelCount
            if (aligned <= 0) return
            buffer.asShortBuffer().put(samples, offset, aligned)
            encoder.queueInputBuffer(index, 0, aligned * 2, presentationTimeUs(), 0)
            offset += aligned
            emittedFrames += aligned / channelCount
        }
    }

    fun finish() {
        if (finished) return
        finished = true
        while (true) {
            drain(false)
            val index = dequeueInput() ?: continue
            encoder.queueInputBuffer(index, 0, 0, presentationTimeUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            break
        }
        drain(true)
    }

    private fun presentationTimeUs() = emittedFrames * 1_000_000L / sampleRate

    private fun dequeueInput(): Int? = encoder.dequeueInputBuffer(TIMEOUT_US).takeIf { it >= 0 }

    private fun drain(untilEnd: Boolean) {
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, if (untilEnd) TIMEOUT_US else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (untilEnd) continue else return
                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> outputBuffers = encoder.outputBuffers
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    started = true
                }
                index >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        val buffer = outputBuffers[index].apply {
                            position(info.offset)
                            limit(info.offset + info.size)
                        }
                        muxer.writeSampleData(track, buffer, info)
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** @return whether anything was written; an empty file is not worth handing back. */
    fun release(): Boolean {
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        if (started) runCatching { muxer.stop() }
        runCatching { muxer.release() }
        return started
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val STEREO_BIT_RATE = 128_000
        private const val MONO_BIT_RATE = 64_000
    }
}
