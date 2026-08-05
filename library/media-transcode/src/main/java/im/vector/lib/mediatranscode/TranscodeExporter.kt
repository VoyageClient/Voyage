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
import im.vector.lib.mediatranscode.audio.AudioTrackCopier
import im.vector.lib.mediatranscode.audio.AudioTrackTranscoder
import im.vector.lib.mediatranscode.audio.AudioTrackWriter
import im.vector.lib.mediatranscode.gl.InputSurface
import im.vector.lib.mediatranscode.gl.OutputSurface
import timber.log.Timber

/**
 * Re-encodes the selected range. This is the path for an exact trim — an mp4 can only *start* at a
 * sync frame, so a remux would have to begin at the one before the requested point, and decoding
 * lets us drop the frames in between — and the only path that can change geometry.
 *
 * Left at the source geometry the decoder renders straight onto the encoder's input surface: no
 * pixels touch the CPU and there is no GL stage at all. A crop or a resize inserts one, decoder →
 * SurfaceTexture → GLES2 → encoder, and with it a different rotation convention: the GL stage turns
 * the picture itself and the output carries no orientation hint, where the direct path leaves
 * geometry alone and rotates through the hint.
 */
@RequiresApi(18)
internal class TranscodeExporter(private val context: Context) {

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
        var encoderSurface: Surface? = null
        var inputSurface: InputSurface? = null
        var outputSurface: OutputSurface? = null
        var muxer: MuxerSession? = null
        var audio: AudioTrackWriter? = null

        if (!CodecAvailability.hasAvcEncoder()) throw VideoEditException.UnsupportedCodec(source.videoMime)

        val rotation = ((source.rotationDegrees + spec.rotationDegrees) % 360 + 360) % 360
        val swapped = rotation % 180 == 90
        val displayWidth = if (swapped) source.height else source.width
        val displayHeight = if (swapped) source.width else source.height
        val timeMap = SpeedTimeMap(spec.startUs, spec.speed)
        // Any geometry change needs the GL stage, and so does re-timing — it is the only place a
        // frame's timestamp can be set. A crop-free resize just keeps the whole frame.
        val geometry = if (spec.crop == null && spec.targetWidth == null && !spec.isRetimed) {
            null
        } else {
            val crop = spec.crop?.let { floatArrayOf(it.left, it.top, it.right, it.bottom) } ?: WHOLE_FRAME
            CropGeometry.outputFor(displayWidth, displayHeight, crop, spec.targetWidth, spec.targetHeight)
        }
        val outputWidth = geometry?.width ?: source.width
        val outputHeight = geometry?.height ?: source.height

        try {
            extractor.setDataSource(context, spec.sourceUri, null)
            val videoTrack = extractor.firstTrackOf("video/") ?: throw VideoEditException.NoVideoTrack()
            extractor.selectTrack(videoTrack)
            val sourceFormat = extractor.getTrackFormat(videoTrack)

            val targetFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outputWidth, outputHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate(source, spec, displayWidth, displayHeight, outputWidth, outputHeight))
                setInteger(MediaFormat.KEY_FRAME_RATE, sourceFormat.getIntOrNull(MediaFormat.KEY_FRAME_RATE) ?: DEFAULT_FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(targetFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            encoderSurface = encoder.createInputSurface()
            if (geometry != null) {
                inputSurface = InputSurface(encoderSurface)
                inputSurface.makeCurrent()
                outputSurface = OutputSurface(CropGeometry.textureCoords(geometry.crop, rotation), outputWidth, outputHeight)
            }
            encoder.start()

            decoder = MediaCodec.createDecoderByType(source.videoMime).apply {
                configure(sourceFormat, outputSurface?.surface ?: encoderSurface, null, 0)
                start()
            }

            muxer = MuxerSession(spec.outputFile.absolutePath).apply {
                setOrientationHint(if (geometry == null) rotation else 0)
            }
            audio = if (spec.muted) null else createAudioWriter(spec, timeMap)

            extractor.seekTo(spec.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val durationUs = runLoop(
                    Pipeline(extractor, decoder, encoder, inputSurface, outputSurface, muxer, audio),
                    spec, timeMap, progressListener, isActive,
            )
            audio?.pumpUpTo(Long.MAX_VALUE, muxer)

            return VideoEditOutput(
                    file = spec.outputFile,
                    width = if (geometry == null) displayWidth else outputWidth,
                    height = if (geometry == null) displayHeight else outputHeight,
                    durationMs = durationUs / 1000,
                    actualStartUs = spec.startUs,
                    audioDropped = !spec.muted && audio == null && source.audioMime != null,
            )
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            // The renderer's GL objects must go while the context is still current.
            runCatching { outputSurface?.release() }
            runCatching { inputSurface?.release() }
            runCatching { encoderSurface?.release() }
            runCatching { extractor.release() }
            audio?.release()
            muxer?.release()
        }
    }

    private fun createAudioWriter(spec: VideoEditSpec, timeMap: SpeedTimeMap): AudioTrackWriter? {
        if (!spec.isRetimed) return AudioTrackCopier.create(context, spec.sourceUri, spec.endUs)
        val transcoder = AudioTrackTranscoder.create(
                context, spec.sourceUri, spec.startUs, spec.endUs, timeMap, spec.changePitch
        ) ?: return null
        // The muxer needs every track's format before it starts, and the encoded one only exists
        // once the encoder has seen some sound. A device with no usable AAC encoder loses its
        // audio rather than the whole export.
        val primed = runCatching { transcoder.prime() }
                .onFailure { Timber.w(it, "VideoEdit: cannot re-encode the audio, dropping it") }
                .getOrDefault(false)
        if (primed) return transcoder
        transcoder.release()
        return null
    }

    private class Pipeline(
            val extractor: MediaExtractor,
            val decoder: MediaCodec,
            val encoder: MediaCodec,
            val inputSurface: InputSurface?,
            val outputSurface: OutputSurface?,
            val muxer: MuxerSession,
            val audio: AudioTrackWriter?,
    )

    /** @return the duration written, in microseconds. */
    @Suppress("LongMethod", "DEPRECATION", "ComplexMethod", "NestedBlockDepth")
    private fun runLoop(
            pipeline: Pipeline,
            spec: VideoEditSpec,
            timeMap: SpeedTimeMap,
            progressListener: VideoEditProgressListener?,
            isActive: () -> Boolean,
    ): Long {
        val extractor = pipeline.extractor
        val decoder = pipeline.decoder
        val encoder = pipeline.encoder
        val inputSurface = pipeline.inputSurface
        val outputSurface = pipeline.outputSurface
        val muxer = pipeline.muxer
        val audio = pipeline.audio
        // The indexed buffer accessors are API 21+, so the legacy arrays are deliberate here.
        val decoderInputBuffers = decoder.inputBuffers
        var encoderOutputBuffers = encoder.outputBuffers
        val info = MediaCodec.BufferInfo()
        val watchdog = StallWatchdog()
        val rangeUs = timeMap.outputUsFor(spec.endUs).coerceAtLeast(1)

        var baseUs = -1L
        var lastWrittenUs = 0L
        var lastRenderedUs = Long.MIN_VALUE
        var inputDone = false
        var decoderDone = false
        var encoderDone = false
        var lastReportedProgress = -1

        fun drainEncoder(timeoutUs: Long): Boolean {
            val encIndex = encoder.dequeueOutputBuffer(info, timeoutUs)
            when {
                encIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                    encoderOutputBuffers = encoder.outputBuffers
                }
                encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Every track must be added before start(), and the encoded video format is
                    // only known now, so the audio track waits for this moment too.
                    muxer.addVideoTrack(encoder.outputFormat)
                    audio?.format?.let { muxer.addAudioTrack(it) }
                    muxer.start()
                    audio?.rebase(if (baseUs >= 0) baseUs else spec.startUs)
                }
                encIndex >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxer.isStarted) {
                        info.presentationTimeUs = (info.presentationTimeUs - baseUs.coerceAtLeast(0)).coerceAtLeast(0)
                        muxer.writeVideo(encoderOutputBuffers[encIndex], info)
                        audio?.pumpUpTo(info.presentationTimeUs, muxer)
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
                else -> return false
            }
            return true
        }

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
                    // Draining the encoder below overwrites the shared buffer info, so the
                    // decoder's own end-of-stream flag has to be read out first.
                    val decoderEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    // Frames before the cut are decoded (later ones reference them) but not rendered.
                    // Some decoders repeat a timestamp; feeding the encoder a non-increasing one
                    // yields a broken stts, so the duplicate goes rather than the ordering.
                    val render = info.size > 0 && presentationTimeUs >= spec.startUs && !pastEnd &&
                            (outputSurface == null || presentationTimeUs > lastRenderedUs)
                    // Re-timed output is already measured from the cut by the map, so there is
                    // nothing left to rebase — and rebasing it again would undo the map's zero.
                    if (render && baseUs < 0) baseUs = if (spec.isRetimed) 0 else presentationTimeUs
                    decoder.releaseOutputBuffer(outIndex, render)
                    if (render && outputSurface != null && inputSurface != null) {
                        lastRenderedUs = presentationTimeUs
                        // Draining first: eglSwapBuffers blocks until the encoder has a free input
                        // buffer, and only draining its output releases one.
                        while (drainEncoder(0) && !encoderDone) Unit
                        if (!outputSurface.awaitNewImage()) throw VideoEditException.Stalled()
                        outputSurface.drawImage()
                        val outputUs = if (spec.isRetimed) timeMap.outputUsFor(presentationTimeUs) else presentationTimeUs
                        inputSurface.setPresentationTime(outputUs * 1000)
                        inputSurface.swapBuffers()
                    }
                    // Decoding a long run-up to the cut writes nothing, and would otherwise look
                    // like a stall on hardware slow enough to spend 15s getting there.
                    watchdog.poke()
                    if (pastEnd || decoderEos) {
                        decoderDone = true
                        encoder.signalEndOfInputStream()
                    }
                }
            }

            drainEncoder(TIMEOUT_US)
        }

        if (!muxer.isStarted) throw VideoEditException.EmptyRange()
        Timber.d("VideoEdit: re-encoded ${spec.startUs}us..${spec.endUs}us exactly")
        return lastWrittenUs
    }

    /**
     * The caller's bitrate, or the source's scaled by how much of the frame survives — spending the
     * whole budget on a quarter of the pixels only makes the file bigger for no visible gain.
     */
    private fun targetBitrate(
            source: MediaSourceInfo,
            spec: VideoEditSpec,
            displayWidth: Int,
            displayHeight: Int,
            outputWidth: Int,
            outputHeight: Int,
    ): Int {
        spec.targetBitrate?.let { return it.coerceIn(MIN_BITRATE, MAX_BITRATE) }
        val base = if (source.bitrate > 0) source.bitrate.coerceAtMost(MAX_BITRATE) else DEFAULT_BITRATE
        val sourcePixels = (displayWidth.toLong() * displayHeight).coerceAtLeast(1)
        val outputPixels = outputWidth.toLong() * outputHeight
        val scaled = base * (outputPixels.toDouble() / sourcePixels).coerceAtMost(1.0)
        return scaled.toInt().coerceIn(MIN_BITRATE, MAX_BITRATE)
    }

    companion object {
        private const val DEFAULT_FRAME_RATE = 30
        private const val I_FRAME_INTERVAL_SECONDS = 1
        private const val TIMEOUT_US = 10_000L
        private const val MAX_BITRATE = 20_000_000
        private const val DEFAULT_BITRATE = 2_000_000
        private const val MIN_BITRATE = 100_000

        private val WHOLE_FRAME = floatArrayOf(0f, 0f, 1f, 1f)
    }
}
