/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.RequiresApi
import im.vector.lib.mediatranscode.MuxableFormats
import im.vector.lib.mediatranscode.MuxerSession
import im.vector.lib.mediatranscode.firstTrackOf
import im.vector.lib.mediatranscode.getIntOrNull
import im.vector.lib.mediatranscode.sampleFlagsCompat
import timber.log.Timber
import java.nio.ByteBuffer

/**
 * Copies a window of the source audio track through to the muxer without re-encoding, rebased onto
 * the same [baseUs] as the video so the two stay in sync.
 */
@RequiresApi(18)
internal class AudioTrackCopier private constructor(
        private val extractor: MediaExtractor,
        override val format: MediaFormat,
        private val endUs: Long,
) : AudioTrackWriter {

    private var buffer = ByteBuffer.allocate(format.getIntOrNull(MediaFormat.KEY_MAX_INPUT_SIZE) ?: DEFAULT_BUFFER)
    private val info = MediaCodec.BufferInfo()
    private var baseUs = 0L
    private var finished = false

    override fun rebase(baseUs: Long) {
        this.baseUs = baseUs
        extractor.seekTo(baseUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    }

    /** Writes audio up to [videoPtsUs] (rebased). Pass [Long.MAX_VALUE] to drain the window. */
    override fun pumpUpTo(videoPtsUs: Long, muxer: MuxerSession) {
        if (finished || !muxer.hasAudioTrack) return
        while (true) {
            // End of stream is sampleTrackIndex, not sampleTime: AAC priming samples carry a
            // legitimately negative timestamp, and reading -1 as "no more samples" drops the whole
            // track before the first write.
            if (extractor.sampleTrackIndex < 0) {
                finished = true
                return
            }
            val sampleTime = extractor.sampleTime
            if (sampleTime > endUs) {
                finished = true
                return
            }
            val rebased = sampleTime - baseUs
            if (rebased > videoPtsUs) return
            val size = extractor.readSample()
            if (size < 0) {
                finished = true
                return
            }
            if (rebased >= 0) {
                info.set(0, size, rebased, extractor.sampleFlagsCompat())
                muxer.writeAudio(buffer, info)
            }
            extractor.advance()
        }
    }

    private fun MediaExtractor.readSample(): Int {
        buffer.clear()
        return try {
            readSampleData(buffer, 0)
        } catch (e: IllegalArgumentException) {
            // KEY_MAX_INPUT_SIZE understates some sources; grow and read the same sample again.
            buffer = ByteBuffer.allocate(buffer.capacity() * 2)
            readSampleData(buffer, 0)
        }
    }

    override fun release() {
        runCatching { extractor.release() }
    }

    companion object {
        private const val DEFAULT_BUFFER = 256 * 1024

        /** @return null when the source has no audio, or audio an mp4 cannot hold. */
        fun create(context: Context, sourceUri: Uri, endUs: Long): AudioTrackCopier? {
            val extractor = MediaExtractor()
            return try {
                extractor.setDataSource(context, sourceUri, null)
                val index = extractor.firstTrackOf("audio/")
                val format = index?.let { extractor.getTrackFormat(it) }
                val mime = format?.getString(MediaFormat.KEY_MIME)
                if (index == null || format == null || mime == null) {
                    extractor.release()
                    null
                } else if (!MuxableFormats.isMuxableAudio(mime)) {
                    Timber.w("VideoEdit: dropping audio track, $mime is not muxable into mp4")
                    extractor.release()
                    null
                } else {
                    extractor.selectTrack(index)
                    AudioTrackCopier(extractor, format, endUs)
                }
            } catch (e: Exception) {
                Timber.w(e, "VideoEdit: no audio track to copy")
                runCatching { extractor.release() }
                null
            }
        }
    }
}
