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

    fun readFrames(file: File): List<AnimatedFrame>? {
        val image = try {
            WebpImage.create(file.readBytes())
        } catch (t: Throwable) {
            Timber.w(t, "WebP: cannot open source")
            return null
        }
        return try {
            compose(image)
        } catch (t: Throwable) {
            Timber.w(t, "WebP: cannot decode frames")
            null
        } finally {
            runCatching { image.dispose() }
        }
    }

    private fun compose(image: WebpImage): List<AnimatedFrame>? {
        if (image.frameCount <= 0 || image.width <= 0 || image.height <= 0) return null
        val canvasBitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val output = ArrayList<AnimatedFrame>(image.frameCount)

        try {
            for (index in 0 until image.frameCount) {
                val frame = image.getFrame(index) ?: continue
                try {
                    // A frame that does not blend replaces its rectangle outright, alpha and all, so
                    // whatever the previous frame left there has to go first.
                    if (!frame.isBlendWithPreviousFrame) canvas.clearRect(frame, clear)
                    frame.drawOnto(canvas)
                    output.add(AnimatedFrame(
                            bitmap = canvasBitmap.copy(Bitmap.Config.ARGB_8888, false),
                            durationMs = frame.durationMs.coerceAtLeast(MIN_FRAME_DELAY_MS)
                    ))
                    if (frame.shouldDisposeToBackgroundColor()) canvas.clearRect(frame, clear)
                } finally {
                    runCatching { frame.dispose() }
                }
            }
        } catch (t: Throwable) {
            // Half a decode is a run of full-canvas bitmaps with nothing left holding them.
            output.forEach { it.bitmap.recycle() }
            throw t
        } finally {
            canvasBitmap.recycle()
        }
        return output.takeIf { it.isNotEmpty() }
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
