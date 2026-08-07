/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import io.element.android.opusencoder.OggOpusDecoder
import org.matrix.android.sdk.internal.util.use
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.abs

// getInputBuffer/getOutputBuffer are API 21+; pre-21 use the getInputBuffers()/getOutputBuffers() arrays (API 16).
@Suppress("DEPRECATION")
@SuppressLint("NewApi")
private fun MediaCodec.inputBufferCompat(index: Int): ByteBuffer? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) getInputBuffer(index) else inputBuffers.getOrNull(index)

@Suppress("DEPRECATION")
@SuppressLint("NewApi")
private fun MediaCodec.outputBufferCompat(index: Int): ByteBuffer? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) getOutputBuffer(index) else outputBuffers.getOrNull(index)

@SuppressLint("NewApi")
object AudioWaveformExtractor {

    private const val TARGET_SAMPLE_COUNT = 50
    private const val CODEC_TIMEOUT_US = 5_000L

    // Approx. window of audio decoded around each seek point, in microseconds.
    private const val WINDOW_DURATION_US = 80_000L

    fun extract(file: File): List<Int> {
        // Voice messages are Ogg/Opus. The platform MediaCodec only decodes Opus on API 21+ (and not
        // at all below the gated floor), so decode with the bundled libopus first; fall back to the
        // platform decoder for any other audio format.
        decodeOpusPeaks(file)?.let { return it }
        return runExtraction { setDataSource(file.absolutePath) }
    }

    fun extract(context: Context, uri: Uri): List<Int> = runExtraction {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
            setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
        } ?: setDataSource(context, uri, null)
    }

    // Decodes the Ogg/Opus [file] to a temporary 16-bit PCM WAV via libopus and reduces it to peaks.
    // Returns null when the file isn't decodable as Opus, so the caller can fall back.
    private fun decodeOpusPeaks(file: File): List<Int>? {
        val wav = runCatching { File.createTempFile("waveform", ".wav", file.parentFile) }.getOrNull() ?: return null
        return try {
            val result = OggOpusDecoder.create().decodeToWav(file.absolutePath, wav.absolutePath)
            if (result != 0) null else peaksFromWav(wav)
        } catch (t: Throwable) {
            Timber.w(t, "libopus waveform decode failed")
            null
        } finally {
            wav.delete()
        }
    }

    private fun peaksFromWav(wav: File): List<Int>? {
        BufferedInputStream(FileInputStream(wav)).use { input ->
            val riff = ByteArray(12)
            if (!readFully(input, riff) ||
                    String(riff, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                    String(riff, 8, 4, Charsets.US_ASCII) != "WAVE") {
                return null
            }

            var channels = 1
            var bitsPerSample = 16
            var dataSize = -1L
            val chunkHeader = ByteArray(8)
            while (readFully(input, chunkHeader)) {
                val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val size = leInt(chunkHeader, 4).toLong() and 0xFFFFFFFFL
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(size.toInt())
                        if (!readFully(input, fmt)) return null
                        channels = leShort(fmt, 2).toInt()
                        bitsPerSample = leShort(fmt, 14).toInt()
                    }
                    "data" -> {
                        dataSize = size
                    }
                    else -> skipFully(input, size)
                }
                if (dataSize >= 0) break
            }

            if (dataSize <= 0 || bitsPerSample != 16 || channels < 1) return null
            val bytesPerFrame = channels * 2
            val totalFrames = dataSize / bytesPerFrame
            if (totalFrames <= 0) return null

            val peaks = IntArray(TARGET_SAMPLE_COUNT)
            val frame = ByteArray(bytesPerFrame)
            var frameIndex = 0L
            var remaining = dataSize
            while (remaining >= bytesPerFrame && readFully(input, frame)) {
                remaining -= bytesPerFrame
                var frameMax = 0
                var c = 0
                while (c < channels) {
                    val sample = abs(leShort(frame, c * 2).toInt())
                    if (sample > frameMax) frameMax = sample
                    c++
                }
                val bucket = ((frameIndex * TARGET_SAMPLE_COUNT) / totalFrames).toInt().coerceIn(0, TARGET_SAMPLE_COUNT - 1)
                if (frameMax > peaks[bucket]) peaks[bucket] = frameMax
                frameIndex++
            }
            return peaks.toList()
        }
    }

    private fun leShort(buffer: ByteArray, offset: Int): Short =
            ((buffer[offset].toInt() and 0xFF) or ((buffer[offset + 1].toInt() and 0xFF) shl 8)).toShort()

    private fun leInt(buffer: ByteArray, offset: Int): Int =
            (buffer[offset].toInt() and 0xFF) or
                    ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
                    ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
                    ((buffer[offset + 3].toInt() and 0xFF) shl 24)

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) return false
            offset += read
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) return
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun runExtraction(setupDataSource: MediaExtractor.() -> Unit): List<Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) return emptyList()
        val extractor = MediaExtractor()
        return try {
            extractor.setupDataSource()

            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return emptyList()
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return emptyList()

            val sampleRate = format.takeIfHas(MediaFormat.KEY_SAMPLE_RATE)?.getInteger(MediaFormat.KEY_SAMPLE_RATE) ?: 44100
            val channels = format.takeIfHas(MediaFormat.KEY_CHANNEL_COUNT)?.getInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
            val durationUs = format.takeIfHas(MediaFormat.KEY_DURATION)?.getLong(MediaFormat.KEY_DURATION) ?: 0L
            if (durationUs <= 0L) return emptyList()

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val framesPerWindow = ((sampleRate * WINDOW_DURATION_US) / 1_000_000L).toInt().coerceAtLeast(64)
            val peaks = IntArray(TARGET_SAMPLE_COUNT)

            try {
                for (i in 0 until TARGET_SAMPLE_COUNT) {
                    val seekUs = (durationUs * i) / TARGET_SAMPLE_COUNT
                    extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()
                    peaks[i] = decodeWindowPeak(extractor, codec, info, channels, framesPerWindow)
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }

            peaks.toList()
        } catch (t: Throwable) {
            Timber.w(t, "Failed to extract waveform from audio file")
            emptyList()
        } finally {
            extractor.release()
        }
    }

    private fun decodeWindowPeak(
            extractor: MediaExtractor,
            codec: MediaCodec,
            info: MediaCodec.BufferInfo,
            channels: Int,
            framesPerWindow: Int,
    ): Int {
        var peak = 0
        var framesRead = 0
        var inputDone = false
        var sentEos = false

        while (framesRead < framesPerWindow) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = codec.inputBufferCompat(inIdx)
                    val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sentEos = true
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
            if (outIdx < 0) {
                if (inputDone && outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
                continue
            }
            val output = codec.outputBufferCompat(outIdx)
            if (output != null && info.size > 0) {
                output.position(info.offset)
                output.limit(info.offset + info.size)
                val shorts = output.asShortBuffer()
                while (shorts.remaining() > 0 && framesRead < framesPerWindow) {
                    var frameMax = 0
                    var c = 0
                    while (c < channels && shorts.remaining() > 0) {
                        val s = abs(shorts.get().toInt())
                        if (s > frameMax) frameMax = s
                        c++
                    }
                    if (frameMax > peak) peak = frameMax
                    framesRead++
                }
            }
            codec.releaseOutputBuffer(outIdx, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            if (sentEos && info.size == 0) break
        }
        return peak
    }

    private fun MediaFormat.takeIfHas(key: String): MediaFormat? = if (containsKey(key)) this else null
}
