/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import im.vector.lib.mediatranscode.MuxerSession
import im.vector.lib.mediatranscode.SpeedTimeMap
import im.vector.lib.mediatranscode.firstTrackOf
import im.vector.lib.mediatranscode.getIntOrNull
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Decodes the audio and encodes it back to AAC, re-timing it on the way when [retimed]. A speed
 * change needs this path, and so does sound an mp4 cannot carry; anything else is copied through
 * untouched by `AudioTrackCopier`, which is both faster and lossless.
 *
 * The output timestamps come from the number of samples actually emitted rather than from the
 * source, because that is the only clock the sound itself follows. A re-timed track is nudged back
 * towards [timeMap] as it goes — the time stretch lands within a sample or two of the requested
 * speed each time it adjusts, and left alone those roundings would add up to visible lip-sync drift
 * over a long clip. The nudge goes onto the speed asked of the next buffer rather than into the
 * samples: taking the difference out of the PCM put a splice, and so a click, in every buffer.
 * Sound that keeps its timing needs none of this.
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
        private val retimed: Boolean,
        private val volume: Float,
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
    private val outInfo = MediaCodec.BufferInfo()
    private var offsetUs = 0L
    private var scratch = ShortArray(0)
    private var pending = ShortArray(0)
    private var pendingOffset = 0

    private var emittedFrames = 0L

    /** How far the clock had to be pulled forward to keep it out of negative time. */
    private var clockOffsetFrames = 0L

    /** How far ahead (+) or behind (−) the map the clock was left by the last buffer. */
    private var driftFrames = 0L
    private var lastOutputFrames = 0
    private var peakDriftFrames = 0L
    private var splices = 0
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

    /**
     * This track counts from the cut, where the video counts from its first rendered frame — which
     * can only be a whole frame later. Left unshifted the sound would lead the picture by that much.
     */
    override fun rebase(baseUs: Long) {
        offsetUs = (baseUs - startUs).coerceAtLeast(0)
    }

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
        val buffer = encoderInputBuffers[index].apply {
            clear()
            order(ByteOrder.nativeOrder())
        }
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
        if (info.size > 0 && sampleRate > 0 && sourceUs + CUT_TOLERANCE_US >= startUs) {
            val shorts = info.size / 2
            if (scratch.size < shorts) scratch = ShortArray(shorts)
            decoderOutputBuffers[index].apply {
                position(info.offset)
                limit(info.offset + info.size)
                // Decoded PCM is native-endian, where a ByteBuffer reads big-endian until told
                // otherwise — every sample would come out byte-swapped.
                order(ByteOrder.nativeOrder())
            }.asShortBuffer().get(scratch, 0, shorts)
            applyGain(shorts)
            val frames = shorts / channelCount
            // The first sample kept rarely falls exactly on the cut, and starting the clock at zero
            // regardless would shift the whole track against the picture by that difference.
            if (!clockStarted) {
                clockStarted = true
                val mapped = timeMap.outputUsFor(sourceUs) * sampleRate / 1_000_000
                // Never negative: the first kept buffer may begin just before the cut, and the muxer
                // drops every packet timed below zero rather than shifting them.
                emittedFrames = mapped.coerceAtLeast(0)
                // Opus times its first packet before zero by the pre-skip, and clamping that away
                // leaves the clock permanently ahead of the map. Without this the drift correction
                // chases the difference for the whole clip, dropping samples every buffer.
                clockOffsetFrames = emittedFrames - mapped
            }
            pending = if (retimed) {
                processor.speed = trimmedRate()
                settleDrift(processor.process(scratch, 0, frames), sourceUs + frameToUs(frames.toLong()))
            } else {
                // Nothing to re-time, so the samples go out as they came in: bit-exact, and with no
                // per-buffer correction to click.
                scratch.copyOf(shorts)
            }
            pendingOffset = 0
        }
        decoder.releaseOutputBuffer(index, false)
        if (eos) {
            decoderDone = true
            if (retimed && ::processor.isInitialized) {
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

    /** Clamped, not wrapped: a sample amplified past full scale has to flatten, not flip sign. */
    private fun applyGain(count: Int) {
        if (volume == 1f) return
        for (index in 0 until count) {
            scratch[index] = (scratch[index] * volume).roundToInt().coerceIn(MIN_SAMPLE, MAX_SAMPLE).toShort()
        }
    }

    /**
     * The speed to ask of the next buffer: the requested one, leaned on just enough to work off
     * however far the last buffer left the clock from the map. Emitting [driftFrames] fewer samples
     * over a buffer of [lastOutputFrames] is that much more speed, and the lean is capped well below
     * where a listener would hear the tempo move.
     */
    private fun trimmedRate(): Float {
        if (driftFrames == 0L || lastOutputFrames <= 0) return timeMap.rate
        val cap = if (changePitch) MAX_RATE_TRIM_RESAMPLED else MAX_RATE_TRIM_STRETCHED
        val trim = (driftFrames.toDouble() / lastOutputFrames).coerceIn(-cap, cap)
        return (timeMap.rate * (1.0 + trim)).toFloat()
    }

    /**
     * Measures how far this buffer left the clock from the map, for the next one to lean against.
     * Only a gap too large for that to ever catch — a hole in the source, not a rounding — is taken
     * out of the samples, where there is a discontinuity to hide behind anyway.
     */
    private fun settleDrift(processed: ShortArray, sourceEndUs: Long): ShortArray {
        if (processed.isEmpty()) return processed
        val frames = processed.size / channelCount
        val expected = timeMap.outputUsFor(sourceEndUs) * sampleRate / 1_000_000 + clockOffsetFrames
        val drift = emittedFrames + frames - expected
        driftFrames = drift
        lastOutputFrames = frames
        if (abs(drift) > abs(peakDriftFrames)) peakDriftFrames = drift
        val spliceAt = sampleRate / SPLICE_THRESHOLD_DIVISOR
        if (abs(drift) <= spliceAt) return processed
        splices++
        driftFrames = 0
        // Growing the array pads with silence rather than repeating the last frame: a gap held on
        // one sample is a buzz, where nothing at all is what a hole in the sound is supposed to be.
        return processed.copyOf((frames - drift.toInt()).coerceAtLeast(0) * channelCount)
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
                        writeShifted(muxer, buffer, info)
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
        held.forEach { (bytes, bufferInfo) -> writeShifted(muxer, ByteBuffer.wrap(bytes), bufferInfo) }
        held.clear()
    }

    /** Whatever falls before the video's zero belongs to the frames that were cut away. */
    private fun writeShifted(muxer: MuxerSession, buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        val shifted = bufferInfo.presentationTimeUs - offsetUs
        if (shifted < 0) return
        outInfo.set(bufferInfo.offset, bufferInfo.size, shifted, bufferInfo.flags)
        muxer.writeAudio(buffer, outInfo)
    }

    private fun startEncoder(decoderFormat: MediaFormat) {
        sampleRate = decoderFormat.getIntOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: DEFAULT_SAMPLE_RATE
        channelCount = decoderFormat.getIntOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 2
        val pcmEncoding = decoderFormat.getIntOrNull(KEY_PCM_ENCODING)
        Timber.d("VideoEdit: audio decoded as ${sampleRate}Hz x$channelCount, pcm $pcmEncoding, re-timed $retimed")
        if (pcmEncoding != null && pcmEncoding != AudioFormat.ENCODING_PCM_16BIT) {
            Timber.w("VideoEdit: decoder gave PCM encoding $pcmEncoding, not 16-bit — the sound will be noise")
        }
        if (retimed) processor = PcmSpeedProcessor(sampleRate, channelCount, changePitch)
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
        if (peakDriftFrames != 0L) {
            Timber.d("VideoEdit: audio clock drifted at most $peakDriftFrames frames, spliced $splices times")
        }
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

        /** MediaFormat.KEY_PCM_ENCODING, spelled out so it can be read on every level. */
        private const val KEY_PCM_ENCODING = "pcm-encoding"

        /**
         * Resampling carries the pitch with it, where a lean of even a percent is a wobble on a
         * held note — and it hardly needs one, its fractional read position being exact to begin
         * with. The time stretch emits whole pitch periods and so lands further out, but a lean
         * there moves the tempo alone, which at this size nobody hears.
         */
        private const val MAX_RATE_TRIM_RESAMPLED = 0.005
        private const val MAX_RATE_TRIM_STRETCHED = 0.02

        /** 50ms — past this the clock is not off by roundings, and no lean would ever catch it. */
        private const val SPLICE_THRESHOLD_DIVISOR = 20

        private const val MIN_SAMPLE = Short.MIN_VALUE.toInt()
        private const val MAX_SAMPLE = Short.MAX_VALUE.toInt()

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
                retimed: Boolean,
                volume: Float,
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
                // Only asked for from 24, where the key exists; below it 16-bit is all there is.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    format.setInteger(KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                }
                decoder = MediaCodec.createDecoderByType(mime).apply {
                    configure(format, null, null, 0)
                    start()
                }
                AudioTrackTranscoder(extractor, decoder, startUs, endUs, timeMap, changePitch, retimed, volume)
            } catch (e: Exception) {
                Timber.w(e, "VideoEdit: cannot decode the audio track, dropping it")
                runCatching { decoder?.stop() }
                runCatching { decoder?.release() }
                runCatching { extractor.release() }
                null
            }
        }
    }
}
