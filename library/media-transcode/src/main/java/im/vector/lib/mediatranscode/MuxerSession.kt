/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer

/**
 * Wraps [MediaMuxer]'s ordering contract: every track must be added before [start], and nothing may
 * be written before it. Samples of both tracks are written interleaved by presentation time, which
 * keeps the result streamable.
 */
@RequiresApi(18)
internal class MuxerSession(outputPath: String) {

    private val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var videoTrack = -1
    private var audioTrack = -1
    private var lastAudioPtsUs = -1L
    private var released = false

    var isStarted = false
        private set

    val hasAudioTrack get() = audioTrack >= 0

    fun setOrientationHint(degrees: Int) {
        muxer.setOrientationHint(((degrees % 360) + 360) % 360)
    }

    fun addVideoTrack(format: MediaFormat) {
        videoTrack = muxer.addTrack(format)
    }

    fun addAudioTrack(format: MediaFormat) {
        audioTrack = muxer.addTrack(format)
    }

    fun start() {
        muxer.start()
        isStarted = true
    }

    /**
     * No monotonic-PTS guard: samples arrive in decode order, where B-frame timestamps legitimately
     * go backwards, and dropping those frames corrupts the picture.
     */
    fun writeVideo(buffer: ByteBuffer, info: MediaCodec.BufferInfo) = write(videoTrack, buffer, info)

    fun writeAudio(buffer: ByteBuffer, info: MediaCodec.BufferInfo): Boolean {
        if (info.presentationTimeUs <= lastAudioPtsUs) return false
        lastAudioPtsUs = info.presentationTimeUs
        return write(audioTrack, buffer, info)
    }

    private fun write(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo): Boolean {
        if (!isStarted || track < 0 || info.size <= 0) return false
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        muxer.writeSampleData(track, buffer, info)
        return true
    }

    fun release() {
        if (released) return
        released = true
        if (isStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }
}
