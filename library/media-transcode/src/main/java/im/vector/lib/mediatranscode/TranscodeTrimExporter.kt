/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.view.Surface
import androidx.annotation.RequiresApi
import timber.log.Timber

/**
 * Trims at an exact frame by re-encoding: an mp4 can only *start* at a sync frame, so a remux would
 * have to begin at the one before the requested point. Decoding lets us drop the frames in between.
 * The decoder renders straight onto the encoder's input surface — no pixels touch the CPU, and with
 * no crop to apply there is no GL stage either.
 */
@RequiresApi(18)
internal class TranscodeTrimExporter(private val context: Context) {

    @Suppress("LongMethod")
    fun export(
            spec: VideoEditSpec,
            source: MediaSourceInfo,
            progressListener: VideoEditProgressListener?,
            isActive: () -> Boolean,
    ): VideoEditOutput {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var inputSurface: Surface? = null
        var muxer: MuxerSession? = null
        var audioCopier: AudioTrackCopier? = null

        if (!CodecAvailability.hasAvcEncoder()) throw VideoEditException.UnsupportedCodec(source.videoMime)

        try {
            extractor.setDataSource(context, spec.sourceUri, null)
            val videoTrack = extractor.firstTrackOf("video/") ?: throw VideoEditException.NoVideoTrack()
            extractor.selectTrack(videoTrack)
            val sourceFormat = extractor.getTrackFormat(videoTrack)

            val targetFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, source.width, source.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate(source))
                setInteger(MediaFormat.KEY_FRAME_RATE, sourceFormat.getIntOrNull(MediaFormat.KEY_FRAME_RATE) ?: DEFAULT_FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(targetFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            inputSurface = encoder.createInputSurface()
            encoder.start()

            decoder = MediaCodec.createDecoderByType(source.videoMime).apply {
                configure(sourceFormat, inputSurface, null, 0)
                start()
            }

            muxer = MuxerSession(spec.outputFile.absolutePath).apply {
                setOrientationHint(source.rotationDegrees + spec.rotationDegrees)
            }
            audioCopier = if (spec.muted) null else AudioTrackCopier.create(context, spec.sourceUri, spec.endUs)

            extractor.seekTo(spec.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val durationUs = runLoop(extractor, decoder, encoder, muxer, audioCopier, spec, progressListener, isActive)
            audioCopier?.pumpUpTo(Long.MAX_VALUE, muxer)

            val rotation = ((source.rotationDegrees + spec.rotationDegrees) % 360 + 360) % 360
            val swapped = rotation % 180 == 90
            return VideoEditOutput(
                    file = spec.outputFile,
                    width = if (swapped) source.height else source.width,
                    height = if (swapped) source.width else source.height,
                    durationMs = durationUs / 1000,
                    actualStartUs = spec.startUs,
                    audioDropped = !spec.muted && audioCopier == null && source.audioMime != null,
            )
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { inputSurface?.release() }
            runCatching { extractor.release() }
            audioCopier?.release()
            muxer?.release()
        }
    }

    /** @return the duration written, in microseconds. */
    @Suppress("LongParameterList", "LongMethod", "DEPRECATION", "ComplexMethod")
    private fun runLoop(
            extractor: MediaExtractor,
            decoder: MediaCodec,
            encoder: MediaCodec,
            muxer: MuxerSession,
            audioCopier: AudioTrackCopier?,
            spec: VideoEditSpec,
            progressListener: VideoEditProgressListener?,
            isActive: () -> Boolean,
    ): Long {
        // The indexed buffer accessors are API 21+, so the legacy arrays are deliberate here.
        val decoderInputBuffers = decoder.inputBuffers
        var encoderOutputBuffers = encoder.outputBuffers
        val info = MediaCodec.BufferInfo()
        val watchdog = StallWatchdog()
        val rangeUs = (spec.endUs - spec.startUs).coerceAtLeast(1)

        var baseUs = -1L
        var lastWrittenUs = 0L
        var inputDone = false
        var decoderDone = false
        var encoderDone = false
        var lastReportedProgress = -1

        while (!encoderDone) {
            if (!isActive()) throw InterruptedException("Export cancelled")
            if (watchdog.isStalled()) throw VideoEditException.Stalled()

            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val size = extractor.readSampleData(decoderInputBuffers[inIndex].apply { clear() }, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            if (!decoderDone) {
                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex >= 0) {
                    val presentationTimeUs = info.presentationTimeUs
                    val pastEnd = presentationTimeUs > spec.endUs
                    // Frames before the cut are decoded (later ones reference them) but not rendered.
                    val render = info.size > 0 && presentationTimeUs >= spec.startUs && !pastEnd
                    if (render && baseUs < 0) baseUs = presentationTimeUs
                    decoder.releaseOutputBuffer(outIndex, render)
                    // Decoding a long run-up to the cut writes nothing, and would otherwise look
                    // like a stall on hardware slow enough to spend 15s getting there.
                    watchdog.poke()
                    if (pastEnd || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        decoderDone = true
                        encoder.signalEndOfInputStream()
                    }
                }
            }

            val encIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                encIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                    encoderOutputBuffers = encoder.outputBuffers
                }
                encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Every track must be added before start(), and the encoded video format is
                    // only known now, so the audio track waits for this moment too.
                    muxer.addVideoTrack(encoder.outputFormat)
                    audioCopier?.let { muxer.addAudioTrack(it.format) }
                    muxer.start()
                    audioCopier?.seekTo(if (baseUs >= 0) baseUs else spec.startUs)
                }
                encIndex >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxer.isStarted) {
                        info.presentationTimeUs = (info.presentationTimeUs - baseUs.coerceAtLeast(0)).coerceAtLeast(0)
                        muxer.writeVideo(encoderOutputBuffers[encIndex], info)
                        audioCopier?.pumpUpTo(info.presentationTimeUs, muxer)
                        lastWrittenUs = info.presentationTimeUs
                        watchdog.poke()
                        val progress = (lastWrittenUs * 100 / rangeUs).toInt().coerceIn(0, 99)
                        if (progress != lastReportedProgress) {
                            lastReportedProgress = progress
                            progressListener?.onProgress(progress)
                        }
                    }
                    encoder.releaseOutputBuffer(encIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                }
            }
        }

        if (!muxer.isStarted) throw VideoEditException.EmptyRange()
        Timber.d("VideoEdit: re-encoded ${spec.startUs}us..${spec.endUs}us exactly")
        return lastWrittenUs
    }

    /** Keep the source bitrate — the upload pipeline compresses afterwards if it needs to. */
    private fun targetBitrate(source: MediaSourceInfo): Int {
        if (source.bitrate > 0) return source.bitrate.coerceAtMost(MAX_BITRATE)
        return DEFAULT_BITRATE
    }

    companion object {
        private const val DEFAULT_FRAME_RATE = 30
        private const val I_FRAME_INTERVAL_SECONDS = 1
        private const val TIMEOUT_US = 10_000L
        private const val MAX_BITRATE = 20_000_000
        private const val DEFAULT_BITRATE = 2_000_000
    }
}
