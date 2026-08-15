/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.RequiresApi
import im.vector.lib.mediatranscode.MuxerSession
import im.vector.lib.mediatranscode.ShortArrayBuilder
import im.vector.lib.mediatranscode.SpeedTimeMap
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The sound of a reversed clip: the whole range is decoded to PCM, turned round a sample frame at a
 * time, then encoded to AAC. Holding it all costs about 170 KB a second, so a long clip is left
 * silent rather than filling the heap.
 */
@RequiresApi(18)
@Suppress("DEPRECATION")
internal class ReversedAudioWriter private constructor(
        private val pcm: ShortArray,
        private val sampleRate: Int,
        private val channelCount: Int,
) : AudioTrackWriter {

    private lateinit var encoder: MediaCodec
    private var encoderInputBuffers: Array<ByteBuffer> = emptyArray()
    private var encoderOutputBuffers: Array<ByteBuffer> = emptyArray()

    private val info = MediaCodec.BufferInfo()
    private val outInfo = MediaCodec.BufferInfo()
    private var offsetUs = 0L
    private var readOffset = 0
    private var emittedFrames = 0L
    private var inputDone = false
    private var encoderDone = false

    /** Encoded before the muxer had its tracks; written out as soon as it starts. */
    private val held = mutableListOf<Pair<ByteArray, MediaCodec.BufferInfo>>()

    override var format: MediaFormat? = null
        private set

    override fun rebase(baseUs: Long) {
        offsetUs = baseUs.coerceAtLeast(0)
    }

    private val lastOutputUs get() = emittedFrames * 1_000_000L / sampleRate

    /** Runs until the encoded format is known, which the muxer needs before it can start. */
    fun prime(): Boolean {
        startEncoder()
        var guard = 0
        while (format == null && !encoderDone && guard++ < PRIME_LIMIT) {
            step(null)
        }
        if (format == null) Timber.w("VideoEdit: reversed audio encoder never reported a format")
        return format != null
    }

    override fun pumpUpTo(videoPtsUs: Long, muxer: MuxerSession) {
        flushHeld(muxer)
        var idle = 0
        while (!encoderDone && lastOutputUs <= videoPtsUs) {
            if (step(muxer)) idle = 0 else if (++idle > IDLE_LIMIT) break
        }
        flushHeld(muxer)
    }

    private fun step(muxer: MuxerSession?): Boolean {
        val fed = feedEncoder()
        return drainEncoder(muxer) || fed
    }

    private fun feedEncoder(): Boolean {
        if (inputDone) return false
        val index = encoder.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return false
        if (readOffset >= pcm.size) {
            encoder.queueInputBuffer(index, 0, 0, lastOutputUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
            return true
        }
        val buffer = encoderInputBuffers[index].apply {
            clear()
            order(ByteOrder.nativeOrder())
        }
        val shorts = minOf(buffer.capacity() / 2, pcm.size - readOffset)
        // A partial frame in an encoder buffer offsets every channel after it.
        val aligned = shorts - shorts % channelCount
        buffer.asShortBuffer().put(pcm, readOffset, aligned)
        encoder.queueInputBuffer(index, 0, aligned * 2, lastOutputUs, 0)
        readOffset += aligned
        emittedFrames += aligned / channelCount
        return true
    }

    private fun drainEncoder(muxer: MuxerSession?): Boolean {
        if (encoderDone) return false
        val index = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
        when {
            index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> encoderOutputBuffers = encoder.outputBuffers
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> format = encoder.outputFormat
            index >= 0 -> {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0) {
                    val buffer = encoderOutputBuffers[index].apply {
                        position(info.offset)
                        limit(info.offset + info.size)
                    }
                    if (muxer != null && muxer.hasAudioTrack && muxer.isStarted) {
                        write(muxer, buffer, info)
                    } else {
                        val copy = ByteArray(info.size)
                        buffer.get(copy)
                        held.add(copy to MediaCodec.BufferInfo().apply {
                            set(0, info.size, info.presentationTimeUs, info.flags)
                        })
                    }
                }
                encoder.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
            }
            else -> return false
        }
        return true
    }

    private fun flushHeld(muxer: MuxerSession) {
        if (held.isEmpty() || !muxer.hasAudioTrack || !muxer.isStarted) return
        held.forEach { (bytes, bufferInfo) -> write(muxer, ByteBuffer.wrap(bytes), bufferInfo) }
        held.clear()
    }

    private fun write(muxer: MuxerSession, buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        val shifted = bufferInfo.presentationTimeUs - offsetUs
        if (shifted < 0) return
        outInfo.set(bufferInfo.offset, bufferInfo.size, shifted, bufferInfo.flags)
        muxer.writeAudio(buffer, outInfo)
    }

    private fun startEncoder() {
        val target = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (channelCount > 1) STEREO_BIT_RATE else MONO_BIT_RATE)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(target, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        encoderInputBuffers = encoder.inputBuffers
        encoderOutputBuffers = encoder.outputBuffers
    }

    override fun release() {
        if (::encoder.isInitialized) {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val PRIME_LIMIT = 500
        private const val IDLE_LIMIT = 50
        private const val STEREO_BIT_RATE = 128_000
        private const val MONO_BIT_RATE = 64_000

        /** All of it is held at once: past this the sound goes rather than the heap. */
        private const val PCM_BUDGET_SHORTS = 40 * 1024 * 1024 / 2

        /** @return null when the range cannot be decoded, or is too long to hold. */
        @Suppress("LongParameterList")
        fun create(
                context: Context,
                sourceUri: Uri,
                startUs: Long,
                endUs: Long,
                volume: Float,
                speed: Float,
                changePitch: Boolean,
        ): ReversedAudioWriter? {
            val decoded = decode(context, sourceUri, startUs, endUs) ?: return null
            val (samples, sampleRate, channelCount) = decoded
            if (samples.isEmpty()) return null
            reverseFrames(samples, channelCount, volume)
            val retimed = retime(samples, sampleRate, channelCount, speed, changePitch)
            return ReversedAudioWriter(retimed, sampleRate, channelCount)
        }

        private data class Decoded(val samples: ShortArray, val sampleRate: Int, val channelCount: Int)

        private fun decode(context: Context, sourceUri: Uri, startUs: Long, endUs: Long): Decoded? {
            val decoder = PcmDecoder.create(context, sourceUri, startUs, endUs) ?: return null
            return try {
                val collected = ShortArrayBuilder()
                var overflowed = false
                decoder.decode { samples, count ->
                    overflowed = collected.size + count > PCM_BUDGET_SHORTS
                    if (!overflowed) collected.append(samples, count)
                    !overflowed
                }
                when {
                    overflowed -> {
                        Timber.w("VideoEdit: too much audio to reverse, dropping the track")
                        null
                    }
                    decoder.sampleRate <= 0 || decoder.channelCount <= 0 -> null
                    else -> Decoded(collected.toShortArray(), decoder.sampleRate, decoder.channelCount)
                }
            } finally {
                decoder.release()
            }
        }

        /** Turns the samples round in place, keeping each frame's channels in their own order. */
        private fun reverseFrames(samples: ShortArray, channelCount: Int, volume: Float) {
            val frames = samples.size / channelCount
            for (frame in 0 until frames / 2) {
                val low = frame * channelCount
                val high = (frames - 1 - frame) * channelCount
                for (channel in 0 until channelCount) {
                    val swap = samples[low + channel]
                    samples[low + channel] = samples[high + channel]
                    samples[high + channel] = swap
                }
            }
            if (volume != 1f) {
                for (index in samples.indices) {
                    samples[index] = (samples[index] * volume).toInt().coerceIn(MIN_SAMPLE, MAX_SAMPLE).toShort()
                }
            }
        }

        private fun retime(
                samples: ShortArray,
                sampleRate: Int,
                channelCount: Int,
                speed: Float,
                changePitch: Boolean,
        ): ShortArray {
            if (!SpeedTimeMap.retimes(speed)) return samples
            val processor = PcmSpeedProcessor(sampleRate, channelCount, changePitch).apply { this.speed = speed }
            val processed = processor.process(samples, 0, samples.size / channelCount)
            val tail = processor.endOfStream()
            return if (tail.isEmpty()) processed else processed + tail
        }

        private const val MIN_SAMPLE = Short.MIN_VALUE.toInt()
        private const val MAX_SAMPLE = Short.MAX_VALUE.toInt()
    }
}
