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
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import im.vector.lib.mediatranscode.firstTrackOf
import im.vector.lib.mediatranscode.getIntOrNull
import timber.log.Timber
import java.nio.ByteOrder

/** Decodes a range of an audio track to 16-bit interleaved PCM, a decoder buffer at a time. */
@RequiresApi(18)
@Suppress("DEPRECATION")
internal class PcmDecoder private constructor(
        private val extractor: MediaExtractor,
        private val decoder: MediaCodec,
        private val startUs: Long,
        private val endUs: Long,
) {

    var sampleRate = 0
        private set

    var channelCount = 0
        private set

    private var scratch = ShortArray(0)

    /**
     * Runs the whole range through [onPcm], which is handed a buffer and how much of it is filled.
     * Returning false from it stops the decode early.
     */
    fun decode(onPcm: (ShortArray, Int) -> Boolean) {
        val inputBuffers = decoder.inputBuffers
        var outputBuffers = decoder.outputBuffers
        val info = MediaCodec.BufferInfo()
        var inputDone = false

        while (true) {
            if (!inputDone) {
                val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    val sampleTime = extractor.sampleTime
                    val size = extractor.readSampleData(inputBuffers[index].apply { clear() }, 0)
                    if (size < 0 || sampleTime > endUs) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(index, 0, size, sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> outputBuffers = decoder.outputBuffers
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> readFormat(decoder.outputFormat)
                index >= 0 -> {
                    // The first kept buffer rarely begins exactly on the cut, and dropping a whole
                    // one for straddling it would lose 20-odd milliseconds of sound.
                    val keep = info.size > 0 && sampleRate > 0 &&
                            info.presentationTimeUs + CUT_TOLERANCE_US >= startUs
                    var wantMore = true
                    if (keep) {
                        val shorts = info.size / 2
                        if (scratch.size < shorts) scratch = ShortArray(shorts)
                        outputBuffers[index].apply {
                            position(info.offset)
                            limit(info.offset + info.size)
                            // Decoded PCM is native-endian, where a ByteBuffer reads big-endian
                            // until told otherwise — every sample would come out byte-swapped.
                            order(ByteOrder.nativeOrder())
                        }.asShortBuffer().get(scratch, 0, shorts)
                        wantMore = onPcm(scratch, shorts)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(index, false)
                    if (eos || !wantMore) return
                }
            }
        }
    }

    private fun readFormat(format: MediaFormat) {
        sampleRate = format.getIntOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: DEFAULT_SAMPLE_RATE
        channelCount = format.getIntOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: DEFAULT_CHANNELS
        val pcmEncoding = format.getIntOrNull(KEY_PCM_ENCODING)
        if (pcmEncoding != null && pcmEncoding != AudioFormat.ENCODING_PCM_16BIT) {
            Timber.w("AudioEdit: decoder gave PCM encoding $pcmEncoding, not 16-bit — the sound will be noise")
        }
    }

    fun release() {
        runCatching { decoder.stop() }
        runCatching { decoder.release() }
        runCatching { extractor.release() }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val CUT_TOLERANCE_US = 25_000L
        private const val DEFAULT_SAMPLE_RATE = 44_100
        private const val DEFAULT_CHANNELS = 2

        /** MediaFormat.KEY_PCM_ENCODING, spelled out so it can be read on every level. */
        private const val KEY_PCM_ENCODING = "pcm-encoding"

        /** @return null when there is no audio track, or nothing on the device decodes it. */
        fun create(context: Context, sourceUri: Uri, startUs: Long, endUs: Long): PcmDecoder? {
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
                PcmDecoder(extractor, decoder, startUs, endUs)
            } catch (e: Exception) {
                Timber.w(e, "AudioEdit: cannot decode the audio track")
                runCatching { decoder?.stop() }
                runCatching { decoder?.release() }
                runCatching { extractor.release() }
                null
            }
        }
    }
}
