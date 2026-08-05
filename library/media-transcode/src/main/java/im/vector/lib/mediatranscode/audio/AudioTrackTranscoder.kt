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
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.RequiresApi
import im.vector.lib.mediatranscode.MuxerSession
import im.vector.lib.mediatranscode.SpeedTimeMap
import im.vector.lib.mediatranscode.firstTrackOf
import im.vector.lib.mediatranscode.getIntOrNull
import timber.log.Timber
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Decodes the audio, re-times it, and encodes it back to AAC. This is the path a speed change
 * needs; without one the track is copied through untouched by `AudioTrackCopier`, which is both
 * faster and lossless but cannot change how long the sound lasts.
 *
 * The output timestamps come from the number of samples actually emitted rather than from the
 * source, because that is the only clock the sound itself follows. They are nudged back towards
 * [timeMap] as they go — the time stretch lands within a sample or two of the requested speed each
 * time it adjusts, and left alone those roundings would add up to visible lip-sync drift over a
 * long clip.
 */
@RequiresApi(18)
@Suppress("DEPRECATION")
internal class AudioTrackTranscoder private constructor(
        private val extractor: MediaExtractor,
        private val decoder: MediaCodec,
        private val startUs: Long,
        private val endUs: Long,
        private val timeMap: SpeedTimeMap,
        private val changePitch: Boolean,
) : AudioTrackWriter {

    private lateinit var encoder: MediaCodec
    private lateinit var processor: PcmSpeedProcessor
    private var sampleRate = 0
    private var channelCount = 0

    private val decoderInputBuffers = decoder.inputBuffers
    private var decoderOutputBuffers = decoder.outputBuffers
    private var encoderInputBuffers: Array<ByteBuffer> = emptyArray()
    private var encoderOutputBuffers: Array<ByteBuffer> = emptyArray()

    private val info = MediaCodec.BufferInfo()
    private var scratch = ShortArray(0)
    private var pending = ShortArray(0)
    private var pendingOffset = 0

    private var emittedFrames = 0L
    private var inputDone = false
    private var decoderDone = false
    private var encoderDone = false
    private var pcmDone = false
    private var clockStarted = false

    /** Encoded before the muxer had its tracks; written out as soon as it starts. */
    private val held = mutableListOf<Pair<ByteArray, MediaCodec.BufferInfo>>()

    /** The encoded format, known only once the encoder has seen enough input. */
    override var format: MediaFormat? = null
        private set

    /** The video's own zero is [SpeedTimeMap]'s, which this track already follows. */
    override fun rebase(baseUs: Long) = Unit

    private val lastOutputUs get() = frameToUs(emittedFrames)

    /**
     * Runs until the encoded format is known, which is what the muxer needs before it can start.
     * @return false when the track cannot be transcoded and should simply be dropped.
     */
    fun prime(): Boolean {
        var guard = 0
        while (format == null && !encoderDone && guard++ < PRIME_LIMIT) {
            step(null)
        }
        if (format == null) Timber.w("VideoEdit: audio encoder never reported a format")
        return format != null
    }

    /** Writes encoded audio out to (but not past) [videoPtsUs]. */
    override fun pumpUpTo(videoPtsUs: Long, muxer: MuxerSession) {
        flushHeld(muxer)
        var idle = 0
        while (!encoderDone && lastOutputUs <= videoPtsUs) {
            // A codec with nothing to say right now is normal; only a long run of them means the
            // track is finished as far as it is concerned, and stopping early truncates the sound.
            if (step(muxer)) idle = 0 else if (++idle > IDLE_LIMIT) break
        }
        flushHeld(muxer)
    }

    /** @return whether anything moved; false means the codecs need more time. */
    private fun step(muxer: MuxerSession?): Boolean {
        var progressed = feedDecoder()
        progressed = feedEncoder() || progressed
        return drainEncoder(muxer) || progressed
    }

    private fun feedDecoder(): Boolean {
        if (inputDone) return false
        val index = decoder.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return false
        val sampleTime = extractor.sampleTime
        val size = extractor.readSampleData(decoderInputBuffers[index].apply { clear() }, 0)
        if (size < 0 || sampleTime > endUs) {
            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            decoder.queueInputBuffer(index, 0, size, sampleTime, 0)
            extractor.advance()
        }
        return true
    }

    private fun feedEncoder(): Boolean {
        if (pcmDone) return false
        if (pendingOffset >= pending.size && !decoderDone) {
            if (!decodeMore()) return false
        }
        if (!::encoder.isInitialized) return false

        if (pendingOffset >= pending.size) {
            if (!decoderDone) return false
            val index = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) return false
            encoder.queueInputBuffer(index, 0, 0, frameToUs(emittedFrames), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            pcmDone = true
            return true
        }

        val index = encoder.dequeueInputBuffer(TIMEOUT_US)
        if (index < 0) return false
        val buffer = encoderInputBuffers[index].apply { clear() }
        val shorts = minOf(buffer.capacity() / 2, pending.size - pendingOffset)
        // A partial frame in an encoder buffer offsets every channel after it.
        val aligned = shorts - shorts % channelCount
        buffer.asShortBuffer().put(pending, pendingOffset, aligned)
        encoder.queueInputBuffer(index, 0, aligned * 2, frameToUs(emittedFrames), 0)
        pendingOffset += aligned
        emittedFrames += aligned / channelCount
        return true
    }

    private fun decodeMore(): Boolean {
        val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
        when {
            index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                decoderOutputBuffers = decoder.outputBuffers
                return true
            }
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                startEncoder(decoder.outputFormat)
                return true
            }
            index < 0 -> return false
        }
        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        val sourceUs = info.presentationTimeUs
        if (info.size > 0 && ::processor.isInitialized && sourceUs + CUT_TOLERANCE_US >= startUs) {
            val shorts = info.size / 2
            if (scratch.size < shorts) scratch = ShortArray(shorts)
            decoderOutputBuffers[index].apply {
                position(info.offset)
                limit(info.offset + info.size)
            }.asShortBuffer().get(scratch, 0, shorts)
            val frames = shorts / channelCount
            // The first sample kept rarely falls exactly on the cut, and starting the clock at zero
            // regardless would shift the whole track against the picture by that difference.
            if (!clockStarted) {
                clockStarted = true
                // Never negative: the first kept buffer may begin just before the cut, and the muxer
                // drops every packet timed below zero rather than shifting them.
                emittedFrames = (timeMap.outputUsFor(sourceUs) * sampleRate / 1_000_000).coerceAtLeast(0)
            }
            processor.speed = timeMap.rate
            val processed = processor.process(scratch, 0, frames)
            pending = correctDrift(processed, sourceUs + frameToUs(frames.toLong()))
            pendingOffset = 0
        }
        decoder.releaseOutputBuffer(index, false)
        if (eos) {
            decoderDone = true
            if (::processor.isInitialized) {
                // Appended, not assigned: some decoders flag end-of-stream on a buffer that still
                // carries audio, and overwriting would drop the last 20-odd milliseconds.
                val tail = processor.endOfStream()
                if (tail.isNotEmpty()) {
                    pending = pending.copyOfRange(pendingOffset, pending.size) + tail
                    pendingOffset = 0
                }
            }
        }
        return true
    }

    /**
     * The stretch lands a sample or two off the requested speed each time it adjusts, which would
     * accumulate into lip-sync drift, so it is smoothed back a hundredth of a buffer at a time.
     */
    private fun correctDrift(processed: ShortArray, sourceEndUs: Long): ShortArray {
        if (processed.isEmpty()) return processed
        val frames = processed.size / channelCount
        val expected = timeMap.outputUsFor(sourceEndUs) * sampleRate / 1_000_000
        val drift = emittedFrames + frames - expected
        val abrupt = abs(drift) > frames / 2
        val limit = if (abrupt) abs(drift) else frames / 100 + 1L
        return when {
            drift > 0 -> processed.copyOf((frames - minOf(drift, limit).toInt()).coerceAtLeast(0) * channelCount)
            drift < 0 -> {
                // Silence rather than the last frame repeated: a big gap held on one sample is a
                // buzz, where nothing at all is what a cut sounds like.
                val extra = minOf(-drift, limit).toInt()
                processed.copyOf((frames + extra) * channelCount)
            }
            else -> processed
        }
    }

    private fun drainEncoder(muxer: MuxerSession?): Boolean {
        if (!::encoder.isInitialized || encoderDone) return false
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
                        muxer.writeAudio(buffer, info)
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
        held.forEach { (bytes, bufferInfo) -> muxer.writeAudio(ByteBuffer.wrap(bytes), bufferInfo) }
        held.clear()
    }

    private fun startEncoder(decoderFormat: MediaFormat) {
        sampleRate = decoderFormat.getIntOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: DEFAULT_SAMPLE_RATE
        channelCount = decoderFormat.getIntOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 2
        processor = PcmSpeedProcessor(sampleRate, channelCount, changePitch)
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

    private fun frameToUs(frames: Long) = if (sampleRate == 0) 0L else frames * 1_000_000L / sampleRate

    override fun release() {
        runCatching { decoder.stop() }
        runCatching { decoder.release() }
        if (::encoder.isInitialized) {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
        runCatching { extractor.release() }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L

        /** One decoder buffer's worth: dropping a whole buffer for straddling the cut loses 20-odd ms. */
        private const val CUT_TOLERANCE_US = 25_000L
        private const val DEFAULT_SAMPLE_RATE = 44_100
        private const val STEREO_BIT_RATE = 128_000
        private const val MONO_BIT_RATE = 64_000

        /** Enough steps to get a format out of any codec; past that something is wrong. */
        private const val PRIME_LIMIT = 500
        private const val IDLE_LIMIT = 50

        /** @return null when there is no audio track, or it cannot be decoded. */
        fun create(
                context: Context,
                sourceUri: Uri,
                startUs: Long,
                endUs: Long,
                timeMap: SpeedTimeMap,
                changePitch: Boolean,
        ): AudioTrackTranscoder? {
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            return try {
                extractor.setDataSource(context, sourceUri, null)
                val track = extractor.firstTrackOf("audio/")
                val format = track?.let { extractor.getTrackFormat(it) }
                val mime = format?.getString(MediaFormat.KEY_MIME)
                if (track == null || format == null || mime == null) {
                    extractor.release()
                    return null
                }
                extractor.selectTrack(track)
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                decoder = MediaCodec.createDecoderByType(mime).apply {
                    configure(format, null, null, 0)
                    start()
                }
                AudioTrackTranscoder(extractor, decoder, startUs, endUs, timeMap, changePitch)
            } catch (e: Exception) {
                Timber.w(e, "VideoEdit: cannot re-time the audio track, dropping it")
                runCatching { decoder?.stop() }
                runCatching { decoder?.release() }
                runCatching { extractor.release() }
                null
            }
        }
    }
}
