/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
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
import im.vector.lib.mediatranscode.audio.AudioTrackWriter
import im.vector.lib.mediatranscode.audio.AudioWriters
import im.vector.lib.mediatranscode.gl.InputSurface
import im.vector.lib.mediatranscode.gl.OffscreenTarget
import im.vector.lib.mediatranscode.gl.OutputSurface
import im.vector.lib.mediatranscode.gl.StoredFrameRenderer
import timber.log.Timber
import java.nio.ByteBuffer

/**
 * Writes the selected range backwards. A decoder can only run forwards, so frames are decoded into
 * memory and handed to the encoder in the other order — which is why this is the one path with a
 * memory budget: the range is cut into runs of frames that fit [FRAME_BUDGET_BYTES], and the runs
 * are played back last-first, each decoded again from the sync frame before it.
 *
 * Everything goes through the GL stage, cropped and rotated on the way in, so a stored frame is
 * already exactly what the encoder should see.
 */
@RequiresApi(18)
internal class ReverseTranscodeExporter(private val context: Context) {

    @Suppress("LongMethod")
    fun export(
            spec: VideoEditSpec,
            source: MediaSourceInfo,
            progressListener: VideoEditProgressListener?,
            isActive: () -> Boolean,
    ): VideoEditOutput {
        if (!CodecAvailability.hasAvcEncoder()) throw VideoEditException.UnsupportedCodec(source.videoMime)

        val rotation = ((source.rotationDegrees + spec.rotationDegrees) % 360 + 360) % 360
        val swapped = rotation % 180 == 90
        val displayWidth = if (swapped) source.height else source.width
        val displayHeight = if (swapped) source.width else source.height
        val crop = spec.crop?.let { floatArrayOf(it.left, it.top, it.right, it.bottom) } ?: WHOLE_FRAME
        val geometry = CropGeometry.outputFor(displayWidth, displayHeight, crop, spec.targetWidth, spec.targetHeight)

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var encoderSurface: Surface? = null
        var inputSurface: InputSurface? = null
        var outputSurface: OutputSurface? = null
        var offscreen: OffscreenTarget? = null
        var storedRenderer: StoredFrameRenderer? = null
        var muxer: MuxerSession? = null
        var audio: AudioTrackWriter? = null

        try {
            extractor.setDataSource(context, spec.sourceUri, null)
            val videoTrack = extractor.firstTrackOf("video/") ?: throw VideoEditException.NoVideoTrack()
            extractor.selectTrack(videoTrack)
            val sourceFormat = extractor.getTrackFormat(videoTrack)

            val frameTimes = collectFrameTimes(extractor, spec)
            if (frameTimes.isEmpty()) throw VideoEditException.EmptyRange()

            val targetFormat = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, geometry.width, geometry.height
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate(source, spec, displayWidth, displayHeight, geometry))
                setInteger(MediaFormat.KEY_FRAME_RATE, sourceFormat.getIntOrNull(MediaFormat.KEY_FRAME_RATE) ?: DEFAULT_FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(targetFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            encoderSurface = encoder.createInputSurface()
            inputSurface = InputSurface(encoderSurface)
            inputSurface.makeCurrent()
            outputSurface = OutputSurface(CropGeometry.textureCoords(geometry.crop, rotation), geometry.width, geometry.height)
            offscreen = OffscreenTarget(geometry.width, geometry.height).apply { setup() }
            storedRenderer = StoredFrameRenderer(geometry.width, geometry.height).apply { setup() }
            encoder.start()

            decoder = MediaCodec.createDecoderByType(source.videoMime).apply {
                configure(sourceFormat, outputSurface.surface, null, 0)
                start()
            }

            muxer = MuxerSession(spec.outputFile.absolutePath).apply { setOrientationHint(0) }
            // The reversed sound is built from the same range, and starts at the output's own zero.
            audio = AudioWriters.create(context, spec, source, spec.startUs, SpeedTimeMap(spec.startUs, spec.speed))

            val durationUs = Run(
                    Pipeline(extractor, decoder, encoder, inputSurface, outputSurface, offscreen, storedRenderer, muxer, audio),
                    spec, frameTimes, geometry, progressListener, isActive
            ).execute()
            audio?.pumpUpTo(Long.MAX_VALUE, muxer)

            val audioDropped = !spec.muted && audio == null && source.audioMime != null
            if (audioDropped) Timber.w("VideoEdit: reversed without the source's ${source.audioMime} track")
            return VideoEditOutput(
                    file = spec.outputFile,
                    width = geometry.width,
                    height = geometry.height,
                    durationMs = durationUs / 1000,
                    actualStartUs = spec.startUs,
                    audioDropped = audioDropped,
            )
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            // The GL objects must go while the context is still current.
            runCatching { storedRenderer?.release() }
            runCatching { offscreen?.release() }
            runCatching { outputSurface?.release() }
            runCatching { inputSurface?.release() }
            runCatching { encoderSurface?.release() }
            runCatching { extractor.release() }
            audio?.release()
            muxer?.release()
        }
    }

    /** Walks the range without decoding, to know how many frames there are and when each is due. */
    private fun collectFrameTimes(extractor: MediaExtractor, spec: VideoEditSpec): List<Long> {
        val times = mutableListOf<Long>()
        extractor.seekTo(spec.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        while (true) {
            val time = extractor.sampleTime
            if (time < 0) break
            if (time > spec.endUs) break
            if (time >= spec.startUs) times.add(time)
            if (!extractor.advance()) break
        }
        // Presentation order, which B-frames put out of step with the order they are stored in.
        times.sort()
        return times
    }

    private class Pipeline(
            val extractor: MediaExtractor,
            val decoder: MediaCodec,
            val encoder: MediaCodec,
            val inputSurface: InputSurface,
            val outputSurface: OutputSurface,
            val offscreen: OffscreenTarget,
            val storedRenderer: StoredFrameRenderer,
            val muxer: MuxerSession,
            val audio: AudioTrackWriter?,
    )

    /** One export, split into runs of frames small enough to hold decoded at once. */
    @Suppress("DEPRECATION")
    private class Run(
            private val pipeline: Pipeline,
            private val spec: VideoEditSpec,
            private val frameTimes: List<Long>,
            geometry: CropGeometry.Output,
            private val progressListener: VideoEditProgressListener?,
            private val isActive: () -> Boolean,
    ) {

        private val frameBytes = geometry.width * geometry.height * BYTES_PER_PIXEL
        private val runLength = (FRAME_BUDGET_BYTES / frameBytes).coerceIn(1, frameTimes.size)
        private val store = ArrayList<ByteBuffer>(runLength)
        private val info = MediaCodec.BufferInfo()
        private val watchdog = StallWatchdog()

        private var encoderOutputBuffers = pipeline.encoder.outputBuffers
        private var encoderDone = false
        private var outputUs = 0L
        private var emitted = 0
        private var lastReportedProgress = -1

        fun execute(): Long {
            Timber.d("VideoEdit: reversing ${frameTimes.size} frames, $runLength at a time")
            // Runs are written last-first, and each run's own frames backwards, which together is
            // the whole range backwards.
            var runEnd = frameTimes.size
            while (runEnd > 0) {
                val runStart = (runEnd - runLength).coerceAtLeast(0)
                decodeRun(runStart, runEnd)
                emitRun()
                runEnd = runStart
            }
            pipeline.encoder.signalEndOfInputStream()
            while (!encoderDone) {
                if (!isActive()) throw InterruptedException("Export cancelled")
                if (watchdog.isStalled()) throw VideoEditException.Stalled()
                drainEncoder(TIMEOUT_US)
            }
            if (!pipeline.muxer.isStarted) throw VideoEditException.EmptyRange()
            return outputUs
        }

        /** Decodes [from, to) into [store], starting at the sync frame before the run. */
        private fun decodeRun(from: Int, to: Int) {
            val decoder = pipeline.decoder
            val extractor = pipeline.extractor
            val firstUs = frameTimes[from]
            val lastUs = frameTimes[to - 1]
            decoder.flush()
            extractor.seekTo(firstUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            // The indexed accessors are API 21+, so the legacy array is deliberate here.
            val decoderInputBuffers = decoder.inputBuffers
            var inputDone = false
            var decoderDone = false
            var lastCapturedUs = Long.MIN_VALUE
            store.forEach { it.clear() }
            var stored = 0

            while (!decoderDone) {
                if (!isActive()) throw InterruptedException("Export cancelled")
                if (watchdog.isStalled()) throw VideoEditException.Stalled()

                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val size = extractor.readSampleData(decoderInputBuffers[index].apply { clear() }, 0)
                        if (size < 0 || extractor.sampleTime > lastUs) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index < 0) continue
                val presentationTimeUs = info.presentationTimeUs
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                // Frames before the run are decoded because later ones reference them, but only
                // the run's own frames are kept — and never a repeated timestamp twice.
                val keep = info.size > 0 && presentationTimeUs >= firstUs && presentationTimeUs <= lastUs &&
                        presentationTimeUs > lastCapturedUs && stored < runLength
                decoder.releaseOutputBuffer(index, keep)
                if (keep) {
                    lastCapturedUs = presentationTimeUs
                    capture(stored++)
                }
                watchdog.poke()
                if (eos || stored == to - from) decoderDone = true
            }
            while (store.size > stored) store.removeAt(store.size - 1)
        }

        /** Draws the frame the decoder just released into the offscreen buffer and keeps the pixels. */
        private fun capture(index: Int) {
            if (!pipeline.outputSurface.awaitNewImage()) throw VideoEditException.Stalled()
            if (index == store.size) store.add(ByteBuffer.allocateDirect(frameBytes))
            pipeline.offscreen.bind()
            pipeline.outputSurface.drawImage()
            pipeline.offscreen.readInto(store[index])
            pipeline.offscreen.unbind()
        }

        private fun emitRun() {
            for (index in store.indices.reversed()) {
                if (!isActive()) throw InterruptedException("Export cancelled")
                // Draining first: swapBuffers blocks until the encoder has a free input buffer, and
                // only draining its output releases one.
                while (drainEncoder(0) && !encoderDone) Unit
                pipeline.storedRenderer.draw(store[index])
                pipeline.inputSurface.setPresentationTime(outputUs * 1000)
                pipeline.inputSurface.swapBuffers()
                outputUs += frameDurationUs(emitted)
                emitted++
            }
        }

        /**
         * How long the frame stays on screen. Reversed frames keep the run of intervals the source
         * had, so a variable frame rate reads back at the same pace it played at.
         */
        private fun frameDurationUs(emittedIndex: Int): Long {
            val sourceIndex = frameTimes.size - 1 - emittedIndex
            val interval = when {
                sourceIndex > 0 -> frameTimes[sourceIndex] - frameTimes[sourceIndex - 1]
                frameTimes.size > 1 -> frameTimes[1] - frameTimes[0]
                else -> DEFAULT_FRAME_INTERVAL_US
            }
            return (interval.coerceIn(MIN_FRAME_INTERVAL_US, MAX_FRAME_INTERVAL_US) / spec.speed).toLong()
        }

        private fun drainEncoder(timeoutUs: Long): Boolean {
            val encoder = pipeline.encoder
            val muxer = pipeline.muxer
            val index = encoder.dequeueOutputBuffer(info, timeoutUs)
            when {
                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> encoderOutputBuffers = encoder.outputBuffers
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxer.addVideoTrack(encoder.outputFormat)
                    pipeline.audio?.format?.let { muxer.addAudioTrack(it) }
                    muxer.start()
                    pipeline.audio?.rebase(0)
                }
                index >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxer.isStarted) {
                        muxer.writeVideo(encoderOutputBuffers[index], info)
                        pipeline.audio?.pumpUpTo(info.presentationTimeUs, muxer)
                        watchdog.poke()
                        reportProgress()
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                }
                else -> return false
            }
            return true
        }

        private fun reportProgress() {
            val progress = (emitted * 100 / frameTimes.size.coerceAtLeast(1)).coerceIn(0, 99)
            if (progress != lastReportedProgress) {
                lastReportedProgress = progress
                progressListener?.onProgress(progress)
            }
        }
    }

    /** The reversing path always resizes through the GL stage, so it prices the output's pixels. */
    private fun targetBitrate(
            source: MediaSourceInfo,
            spec: VideoEditSpec,
            displayWidth: Int,
            displayHeight: Int,
            geometry: CropGeometry.Output,
    ): Int {
        spec.targetBitrate?.let { return it.coerceIn(MIN_BITRATE, MAX_BITRATE) }
        val base = if (source.bitrate > 0) source.bitrate.coerceAtMost(MAX_BITRATE) else DEFAULT_BITRATE
        val sourcePixels = (displayWidth.toLong() * displayHeight).coerceAtLeast(1)
        val outputPixels = geometry.width.toLong() * geometry.height
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
        private const val BYTES_PER_PIXEL = 4

        /** What the frames of one run may cost between them; a run is decoded again to be written. */
        private const val FRAME_BUDGET_BYTES = 48 * 1024 * 1024

        private const val DEFAULT_FRAME_INTERVAL_US = 33_333L

        /** A source gap of a second is a still, not a frame rate; 8ms is faster than any display. */
        private const val MIN_FRAME_INTERVAL_US = 8_000L
        private const val MAX_FRAME_INTERVAL_US = 1_000_000L

        private val WHOLE_FRAME = floatArrayOf(0f, 0f, 1f, 1f)
    }
}
