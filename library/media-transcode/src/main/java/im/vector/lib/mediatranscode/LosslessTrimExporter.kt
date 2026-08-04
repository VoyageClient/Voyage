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
import androidx.annotation.RequiresApi
import timber.log.Timber
import java.nio.ByteBuffer

/**
 * Trims by remuxing the original samples — no codecs involved, so it is fast and lossless. Only
 * usable when the geometry is untouched; cropping needs the transcode path.
 */
@RequiresApi(18)
internal class LosslessTrimExporter(private val context: Context) {

    fun export(
            spec: VideoEditSpec,
            source: MediaSourceInfo,
            progressListener: VideoEditProgressListener?,
            isActive: () -> Boolean,
    ): VideoEditOutput {
        if (!MuxableFormats.isMuxableVideo(source.videoMime)) throw VideoEditException.UnsupportedCodec(source.videoMime)

        val extractor = MediaExtractor()
        var muxer: MuxerSession? = null
        var audioCopier: AudioTrackCopier? = null
        try {
            extractor.setDataSource(context, spec.sourceUri, null)
            val videoTrack = extractor.firstTrackOf("video/") ?: throw VideoEditException.NoVideoTrack()
            extractor.selectTrack(videoTrack)
            val videoFormat = extractor.getTrackFormat(videoTrack)

            extractor.seekTo(spec.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val actualStartUs = extractor.sampleTime
            if (actualStartUs < 0) throw VideoEditException.EmptyRange()

            audioCopier = if (spec.muted) null else AudioTrackCopier.create(context, spec.sourceUri, spec.endUs)
            muxer = MuxerSession(spec.outputFile.absolutePath).apply {
                setOrientationHint(source.rotationDegrees + spec.rotationDegrees)
                try {
                    addVideoTrack(videoFormat)
                    audioCopier?.let { addAudioTrack(it.format) }
                } catch (e: IllegalArgumentException) {
                    // Some OEM muxers reject copied formats carrying vendor keys.
                    throw VideoEditException.MuxerRejected(e)
                }
                start()
            }
            audioCopier?.seekTo(actualStartUs)

            val lastPtsUs = copyVideo(extractor, videoFormat, muxer, audioCopier, spec, actualStartUs, progressListener, isActive)
            audioCopier?.pumpUpTo(Long.MAX_VALUE, muxer)

            val rotation = ((source.rotationDegrees + spec.rotationDegrees) % 360 + 360) % 360
            val swapped = rotation % 180 == 90
            return VideoEditOutput(
                    file = spec.outputFile,
                    width = if (swapped) source.height else source.width,
                    height = if (swapped) source.width else source.height,
                    durationMs = (lastPtsUs - actualStartUs) / 1000,
                    actualStartUs = actualStartUs,
                    audioDropped = !spec.muted && audioCopier == null && source.audioMime != null,
            )
        } finally {
            runCatching { extractor.release() }
            audioCopier?.release()
            muxer?.release()
        }
    }

    @Suppress("LongParameterList")
    private fun copyVideo(
            extractor: MediaExtractor,
            videoFormat: MediaFormat,
            muxer: MuxerSession,
            audioCopier: AudioTrackCopier?,
            spec: VideoEditSpec,
            actualStartUs: Long,
            progressListener: VideoEditProgressListener?,
            isActive: () -> Boolean,
    ): Long {
        var buffer = ByteBuffer.allocate(videoFormat.getIntOrNull(MediaFormat.KEY_MAX_INPUT_SIZE) ?: DEFAULT_BUFFER)
        val info = MediaCodec.BufferInfo()
        val watchdog = StallWatchdog()
        val rangeUs = (spec.endUs - actualStartUs).coerceAtLeast(1)
        var lastPtsUs = actualStartUs
        var lastReportedProgress = -1
        var wrote = false

        while (true) {
            if (!isActive()) throw InterruptedException("Export cancelled")
            if (watchdog.isStalled()) throw VideoEditException.Stalled()

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

            info.set(0, size, sampleTime - actualStartUs, extractor.sampleFlagsCompat())
            if (muxer.writeVideo(buffer, info)) {
                wrote = true
                lastPtsUs = sampleTime
                watchdog.poke()
                audioCopier?.pumpUpTo(info.presentationTimeUs, muxer)
                val progress = ((sampleTime - actualStartUs) * 100 / rangeUs).toInt().coerceIn(0, 99)
                if (progress != lastReportedProgress) {
                    lastReportedProgress = progress
                    progressListener?.onProgress(progress)
                }
            }
            extractor.advance()
        }

        if (!wrote) throw VideoEditException.EmptyRange()
        Timber.d("VideoEdit: trimmed ${actualStartUs}us..${lastPtsUs}us losslessly")
        return lastPtsUs
    }

    companion object {
        private const val DEFAULT_BUFFER = 1024 * 1024
    }
}
