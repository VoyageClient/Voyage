/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import android.graphics.Bitmap
import android.os.Handler
import android.os.SystemClock
import im.vector.lib.animatedimage.AnimatedImageFormat
import java.io.File
import java.util.concurrent.Executors

/**
 * Plays an [AnimatedImageSource] into an [AnimatedFrameView], standing in for the
 * [android.media.MediaPlayer] the video path uses.
 *
 * Speed is honoured on every version here — there is no codec involved, only which frame is due.
 */
class AnimatedFramePlayer(
        private val source: AnimatedImageSource,
        private val sourceFile: File,
        private val format: AnimatedImageFormat?,
        private val frameView: AnimatedFrameView,
        private val handler: Handler,
        private val onPositionChanged: (Long) -> Unit,
) {

    var speed: Float = 1f
    var reversed: Boolean = false
    var loopStartUs: Long = 0
    var loopEndUs: Long = source.durationUs

    var positionUs: Long = 0
        private set

    var isPlaying: Boolean = false
        private set

    private var lastTickAt = 0L

    /** The paused frame decoded afresh at full size, since the held frames are scaled to a memory budget. */
    private var sharpFrame: Bitmap? = null
    private var sharpIndex = -1
    private var sharpRequestedIndex = -1
    @Volatile private var sharpGeneration = 0
    private var released = false
    private val sharpDecoder = Executors.newSingleThreadExecutor()

    fun start() {
        if (isPlaying || source.frames.isEmpty()) return
        if (reversed && positionUs <= loopStartUs) positionUs = loopEndUs
        if (!reversed && positionUs >= loopEndUs) positionUs = loopStartUs
        dropSharpFrame()
        isPlaying = true
        lastTickAt = SystemClock.uptimeMillis()
        handler.post(ticker)
    }

    fun pause() {
        isPlaying = false
        handler.removeCallbacks(ticker)
        source.indexAt(positionUs)?.let { scheduleSharpFrame(it) }
    }

    fun seekTo(us: Long) {
        positionUs = us.coerceIn(0, source.durationUs)
        lastTickAt = SystemClock.uptimeMillis()
        draw()
    }

    fun release() {
        pause()
        released = true
        dropSharpFrame()
        frameView.frame = null
        sharpDecoder.shutdown()
        source.release()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!isPlaying) return
            val now = SystemClock.uptimeMillis()
            val elapsedUs = ((now - lastTickAt) * 1000 * speed).toLong()
            positionUs += if (reversed) -elapsedUs else elapsedUs
            lastTickAt = now
            if (reversed) {
                if (positionUs <= loopStartUs) positionUs = loopEndUs
            } else {
                if (positionUs >= loopEndUs) positionUs = loopStartUs
            }
            draw()
            onPositionChanged(positionUs)
            handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    fun draw() {
        val index = source.indexAt(positionUs) ?: return
        val sharp = sharpFrame?.takeIf { !isPlaying && sharpIndex == index && !it.isRecycled }
        frameView.frame = sharp ?: source.frames[index].bitmap.takeIf { !it.isRecycled } ?: return
        if (!isPlaying && sharp == null) scheduleSharpFrame(index)
    }

    private fun scheduleSharpFrame(index: Int) {
        if (released || !source.isDownscaled || sharpRequestedIndex == index) return
        handler.removeCallbacks(decodeSharpFrame)
        sharpRequestedIndex = index
        handler.postDelayed(decodeSharpFrame, SHARP_FRAME_DELAY_MS)
    }

    private val decodeSharpFrame = Runnable {
        val index = sharpRequestedIndex
        val token = ++sharpGeneration
        val maxDimension = frameView.maxBitmapSize
        sharpDecoder.execute {
            if (token != sharpGeneration) return@execute
            val maxPixels = (Runtime.getRuntime().maxMemory() / 8 / BYTES_PER_PIXEL).toInt()
            val frame = AnimatedImageSource.decodeFrame(sourceFile, format, index, maxPixels, maxDimension)
            handler.post {
                if (token == sharpGeneration && !released && !isPlaying && source.indexAt(positionUs) == index) {
                    sharpFrame = frame
                    sharpIndex = if (frame != null) index else -1
                    draw()
                }
            }
        }
    }

    private fun dropSharpFrame() {
        handler.removeCallbacks(decodeSharpFrame)
        sharpGeneration++
        sharpFrame = null
        sharpIndex = -1
        sharpRequestedIndex = -1
    }

    companion object {
        /** 60 Hz is wasted on animations that rarely exceed 25 frames a second. */
        private const val FRAME_INTERVAL_MS = 33L

        /** Scrubbing passes through frames faster than they can be decoded; only the one it rests on is. */
        private const val SHARP_FRAME_DELAY_MS = 250L
        private const val BYTES_PER_PIXEL = 4
    }
}
