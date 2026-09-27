/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.animatedimage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.bumptech.glide.integration.webp.WebpFrame
import com.bumptech.glide.integration.webp.WebpImage
import timber.log.Timber
import java.io.File

/**
 * Pulls the frames out of an animated WebP, using the same native libwebp decoder that renders them
 * in the timeline. Android's own decoders only ever hand back the first frame.
 *
 * A WebP frame covers a sub-rectangle of the canvas rather than the whole of it, and carries how it
 * should be combined with what is already there, so the frames are composited here — what comes back
 * is a sequence of complete pictures.
 */
object AnimatedWebpReader {

    fun readFrames(file: File): List<AnimatedFrame>? = collectFrames { visitFrames(file, it) }

    fun visitFrames(file: File, onFrame: (AnimatedFrame, Int, Int) -> Unit): Boolean {
        val image = try {
            WebpImage.create(file.readBytes())
        } catch (error: Throwable) {
            Timber.w(error, "WebP: cannot open source")
            return false
        }
        try {
            if (image.frameCount <= 0 || image.width <= 0 || image.height <= 0) return false
            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
            try {
                for (index in 0 until image.frameCount) {
                    val frame = image.getFrame(index) ?: return false
                    try {
                        if (!frame.isBlendWithPreviousFrame) canvas.clearRect(frame, clear)
                        frame.drawOnto(canvas)
                        onFrame(AnimatedFrame(bitmap, frame.durationMs.coerceAtLeast(MIN_FRAME_DELAY_MS)), index, image.frameCount)
                        if (frame.shouldDisposeToBackgroundColor()) canvas.clearRect(frame, clear)
                    } finally {
                        frame.dispose()
                    }
                }
                return true
            } finally {
                bitmap.recycle()
            }
        } finally {
            image.dispose()
        }
    }

    private fun WebpFrame.drawOnto(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val patch = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            renderFrame(width, height, patch)
            canvas.drawBitmap(patch, xOffest.toFloat(), yOffest.toFloat(), null)
        } finally {
            patch.recycle()
        }
    }

    private fun Canvas.clearRect(frame: WebpFrame, clear: Paint) {
        drawRect(
                frame.xOffest.toFloat(),
                frame.yOffest.toFloat(),
                (frame.xOffest + frame.width).toFloat(),
                (frame.yOffest + frame.height).toFloat(),
                clear
        )
    }
}
