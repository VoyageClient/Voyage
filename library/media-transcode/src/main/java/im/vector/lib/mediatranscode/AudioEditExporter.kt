/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import im.vector.lib.mediatranscode.audio.AacFileWriter
import im.vector.lib.mediatranscode.audio.PcmDecoder
import im.vector.lib.mediatranscode.audio.PcmSpeedProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer

/**
 * @property volume how much the sound is scaled by; 1 leaves it alone.
 * @property reversed plays the range backwards, which means holding all of it decoded at once.
 */
data class AudioEditSpec(
        val sourceUri: Uri,
        val startUs: Long,
        val endUs: Long,
        val outputFile: File,
        val speed: Float = 1f,
        val changePitch: Boolean = true,
        val volume: Float = 1f,
        val reversed: Boolean = false,
)

data class AudioEditOutput(val file: File, val durationMs: Long)

/** Whether anything but the cut has to happen, which is what decides a copy from a re-encode. */
private val AudioEditSpec.isProcessed
    get() = reversed || volume != 1f || SpeedTimeMap.retimes(speed)

/** Applies an [AudioEditSpec] to produce an mp4 holding one AAC track. */
object AudioEditExporter {

    /** Writing the result needs MediaMuxer, which is API 18. */
    fun isSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2

    const val OUTPUT_MIME_TYPE = "audio/mp4"

    @RequiresApi(18)
    suspend fun export(
            context: Context,
            spec: AudioEditSpec,
            progressListener: VideoEditProgressListener? = null,
    ): AudioEditOutput = coroutineScope {
        progressListener?.onProgress(0)
        val result = withContext(Dispatchers.Default) {
            runCatching { run(context, spec, progressListener) { isActive } }
                    .onFailure {
                        Timber.w(it, "AudioEdit: export failed")
                        spec.outputFile.delete()
                    }
        }
        result.getOrThrow().also { progressListener?.onProgress(100) }
    }

    @RequiresApi(18)
    private fun run(
            context: Context,
            spec: AudioEditSpec,
            progressListener: VideoEditProgressListener?,
            isActive: () -> Boolean,
    ): AudioEditOutput {
        // A plain trim is a copy: every audio frame stands on its own, so there is nothing a
        // decode and re-encode would buy but time and a generation of quality.
        if (!spec.isProcessed) {
            remux(context, spec, progressListener, isActive)?.let { return it }
        }
        return transcode(context, spec, progressListener, isActive)
    }

    /** @return null when the source cannot be copied into an mp4 and has to be re-encoded. */
    @RequiresApi(18)
    @Suppress("ReturnCount")
    private fun remux(
            context: Context,
            spec: AudioEditSpec,
            progressListener: VideoEditProgressListener?,
            isActive: () -> Boolean,
    ): AudioEditOutput? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(context, spec.sourceUri, null)
            val track = extractor.firstTrackOf("audio/") ?: return null
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            if (!MuxableFormats.isMuxableAudio(mime)) return null
            extractor.selectTrack(track)
            extractor.seekTo(spec.startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val firstUs = extractor.sampleTime
            if (firstUs < 0) return null

            muxer = MediaMuxer(spec.outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputTrack = muxer.addTrack(format)
            muxer.start()
            started = true

            var buffer = ByteBuffer.allocate(format.getIntOrNull(MediaFormat.KEY_MAX_INPUT_SIZE) ?: DEFAULT_BUFFER)
            val info = MediaCodec.BufferInfo()
            val rangeUs = (spec.endUs - firstUs).coerceAtLeast(1)
            var lastUs = firstUs
            var lastReportedProgress = -1
            var wrote = false

            while (true) {
                if (!isActive()) throw InterruptedException("Export cancelled")
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0 || sampleTime > spec.endUs) break
                buffer.clear()
                val size = try {
                    extractor.readSampleData(buffer, 0)
                } catch (e: IllegalArgumentException) {
                    buffer = ByteBuffer.allocate(buffer.capacity() * 2)
                    extractor.readSampleData(buffer, 0)
                }
                if (size < 0) break
                info.set(0, size, sampleTime - firstUs, extractor.sampleFlagsCompat())
                muxer.writeSampleData(outputTrack, buffer, info)
                wrote = true
                lastUs = sampleTime
                val progress = ((sampleTime - firstUs) * 100 / rangeUs).toInt().coerceIn(0, 99)
                if (progress != lastReportedProgress) {
                    lastReportedProgress = progress
                    progressListener?.onProgress(progress)
                }
                extractor.advance()
            }
            if (!wrote) throw VideoEditException.EmptyRange()
            Timber.d("AudioEdit: copied ${firstUs}us..${lastUs}us without re-encoding")
            return AudioEditOutput(spec.outputFile, (lastUs - firstUs) / 1000)
        } catch (e: Exception) {
            if (e is InterruptedException || e is VideoEditException) throw e
            Timber.w(e, "AudioEdit: cannot copy the track through, re-encoding it instead")
            return null
        } finally {
            runCatching { extractor.release() }
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    @RequiresApi(18)
    private fun transcode(
            context: Context,
            spec: AudioEditSpec,
            progressListener: VideoEditProgressListener?,
            isActive: () -> Boolean,
    ): AudioEditOutput {
        val decoder = PcmDecoder.create(context, spec.sourceUri, spec.startUs, spec.endUs)
                ?: throw VideoEditException.UnsupportedCodec("audio")
        var writer: AacFileWriter? = null
        try {
            val rangeUs = (spec.endUs - spec.startUs).coerceAtLeast(1)
            // Reversing has to hear the end before it can write the start, so that path holds the
            // whole range; anything else is written as it is decoded.
            val collected = if (spec.reversed) ShortArrayBuilder() else null
            var lastReportedProgress = -1

            fun openWriter(): AacFileWriter = writer ?: AacFileWriter(
                    decoder.sampleRate, decoder.channelCount, spec.outputFile
            ).also { writer = it }

            val processor = lazy {
                PcmSpeedProcessor(decoder.sampleRate, decoder.channelCount, spec.changePitch)
                        .apply { speed = spec.speed }
            }

            fun emit(samples: ShortArray, count: Int) {
                val gained = applyGain(samples, count, spec.volume)
                if (!SpeedTimeMap.retimes(spec.speed)) {
                    openWriter().write(gained, count)
                    return
                }
                val retimed = processor.value.process(gained, 0, count / decoder.channelCount)
                if (retimed.isNotEmpty()) openWriter().write(retimed, retimed.size)
            }

            decoder.decode { samples, count ->
                if (!isActive()) throw InterruptedException("Export cancelled")
                if (collected != null) {
                    if (collected.size + count > PCM_BUDGET_SHORTS) throw VideoEditException.NotEnoughSpace(
                            collected.size.toLong() * 2
                    )
                    collected.append(samples, count)
                } else {
                    emit(samples, count)
                    val progress = (writer?.durationUs?.times(100)?.div(rangeUs) ?: 0L).toInt().coerceIn(0, 99)
                    if (progress != lastReportedProgress) {
                        lastReportedProgress = progress
                        progressListener?.onProgress(progress)
                    }
                }
                true
            }

            if (collected != null) {
                val samples = collected.toShortArray()
                reverseFrames(samples, decoder.channelCount)
                emit(samples, samples.size)
            }
            if (SpeedTimeMap.retimes(spec.speed) && processor.isInitialized()) {
                val tail = processor.value.endOfStream()
                if (tail.isNotEmpty()) openWriter().write(tail, tail.size)
            }
            val active = writer ?: throw VideoEditException.EmptyRange()
            active.finish()
            val durationUs = active.durationUs
            if (!active.release()) throw VideoEditException.EmptyRange()
            writer = null
            Timber.d("AudioEdit: wrote ${durationUs / 1000}ms, reversed ${spec.reversed}, speed ${spec.speed}")
            return AudioEditOutput(spec.outputFile, durationUs / 1000)
        } finally {
            decoder.release()
            writer?.release()
        }
    }

    /** Clamped, not wrapped: a sample amplified past full scale has to flatten, not flip sign. */
    private fun applyGain(samples: ShortArray, count: Int, volume: Float): ShortArray {
        if (volume == 1f) return samples
        for (index in 0 until count) {
            samples[index] = (samples[index] * volume).toInt().coerceIn(MIN_SAMPLE, MAX_SAMPLE).toShort()
        }
        return samples
    }

    /** Turns the samples round in place, keeping each frame's channels in their own order. */
    private fun reverseFrames(samples: ShortArray, channelCount: Int) {
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
    }

    private const val MIN_SAMPLE = Short.MIN_VALUE.toInt()
    private const val MAX_SAMPLE = Short.MAX_VALUE.toInt()

    /** Reversing holds it all at once: about nine minutes of stereo sound. */
    private const val PCM_BUDGET_SHORTS = 96 * 1024 * 1024 / 2

    private const val DEFAULT_BUFFER = 512 * 1024
}

/** Grows like an ArrayList, but of shorts: boxing tens of megabytes of samples is not an option. */
internal class ShortArrayBuilder {

    private var array = ShortArray(INITIAL_CAPACITY)

    var size = 0
        private set

    fun append(samples: ShortArray, count: Int) {
        ensureCapacity(size + count)
        System.arraycopy(samples, 0, array, size, count)
        size += count
    }

    fun toShortArray(): ShortArray = array.copyOf(size)

    private fun ensureCapacity(required: Int) {
        if (required <= array.size) return
        var capacity = array.size
        while (capacity < required) capacity *= 2
        array = array.copyOf(capacity)
    }

    companion object {
        private const val INITIAL_CAPACITY = 1 shl 16
    }
}
