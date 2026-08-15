/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.Handler
import android.os.SystemClock
import android.view.TextureView

/**
 * Plays an [AnimatedImageSource] into a [TextureView], standing in for the [android.media.MediaPlayer]
 * the video path uses. Frames are painted through `lockCanvas`, stretched to fill the surface exactly
 * as a decoder would, so the crop overlay's transform applies to both alike.
 *
 * Speed is honoured on every version here — there is no codec involved, only which frame is due.
 */
class AnimatedFramePlayer(
        private val source: AnimatedImageSource,
        private val textureView: TextureView,
        private val handler: Handler,
        private val onPositionChanged: (Long) -> Unit,
) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val destination = Rect()

    var speed: Float = 1f
    var reversed: Boolean = false
    var loopStartUs: Long = 0
    var loopEndUs: Long = source.durationUs

    var positionUs: Long = 0
        private set

    var isPlaying: Boolean = false
        private set

    private var lastTickAt = 0L

    fun start() {
        if (isPlaying || source.frames.isEmpty()) return
        if (reversed && positionUs <= loopStartUs) positionUs = loopEndUs
        if (!reversed && positionUs >= loopEndUs) positionUs = loopStartUs
        isPlaying = true
        lastTickAt = SystemClock.uptimeMillis()
        handler.post(ticker)
    }

    fun pause() {
        isPlaying = false
        handler.removeCallbacks(ticker)
    }

    fun seekTo(us: Long) {
        positionUs = us.coerceIn(0, source.durationUs)
        lastTickAt = SystemClock.uptimeMillis()
        draw()
    }

    fun release() {
        pause()
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
        val bitmap = source.frameAt(positionUs)?.takeIf { !it.isRecycled } ?: return
        if (!textureView.isAvailable) return
        val canvas = textureView.lockCanvas() ?: return
        try {
            // The surface holds the previous frame; a transparent animation would otherwise
            // composite each frame over the last one.
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            destination.set(0, 0, canvas.width, canvas.height)
            canvas.drawBitmap(bitmap, null, destination, paint)
        } finally {
            runCatching { textureView.unlockCanvasAndPost(canvas) }
        }
    }

    companion object {
        /** 60 Hz is wasted on animations that rarely exceed 25 frames a second. */
        private const val FRAME_INTERVAL_MS = 33L
    }
}
