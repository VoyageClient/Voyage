/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The locked-ratio maths the image and video crop overlays share. Rectangles are normalised (0..1)
 * against the displayed image or frame, so a ratio in output pixels has to be converted first.
 */
object CropRatio {

    /**
     * [ratio] (width / height in output pixels) expressed in normalised units: a rect of height h
     * must be k * h wide to come out at that ratio once projected back onto the displayed shape.
     */
    fun normalise(ratio: Float?, displayedWidth: Float, displayedHeight: Float): Float? {
        if (displayedWidth <= 0f || displayedHeight <= 0f) return null
        return ratio?.let { it * displayedHeight / displayedWidth }
    }

    /**
     * Re-derives [rect] for ratio [k], keeping its center and fitting inside its old bounds. A rect
     * covering the whole image therefore becomes the largest centered one that ratio allows.
     */
    fun fitAroundCenter(rect: RectF, k: Float) {
        var w = min(rect.width(), rect.height() * k)
        if (w > 1f) w = 1f
        var h = w / k
        if (h > 1f) {
            h = 1f
            w = h * k
        }
        val cx = rect.centerX().coerceIn(w / 2f, 1f - w / 2f)
        val cy = rect.centerY().coerceIn(h / 2f, 1f - h / 2f)
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    /**
     * Resizes [rect] to ratio [k] for a handle dragged to ([nx], [ny]), anchored on the opposite
     * side. [cornerX] and [cornerY] are 0 for the left/top edge, 1 for the right/bottom, and -1 for
     * an axis the handle leaves alone.
     */
    @Suppress("LongParameterList")
    fun resize(rect: RectF, k: Float, nx: Float, ny: Float, cornerX: Int, cornerY: Int, minWidth: Float, minHeight: Float) {
        val anchorX = when (cornerX) {
            0 -> rect.right
            1 -> rect.left
            else -> rect.centerX()
        }
        val anchorY = when (cornerY) {
            0 -> rect.bottom
            1 -> rect.top
            else -> rect.centerY()
        }
        val maxWidth = when (cornerX) {
            0 -> anchorX
            1 -> 1f - anchorX
            else -> 2f * min(anchorX, 1f - anchorX)
        }
        val maxHeight = when (cornerY) {
            0 -> anchorY
            1 -> 1f - anchorY
            else -> 2f * min(anchorY, 1f - anchorY)
        }
        val upper = min(maxWidth, maxHeight * k)
        if (upper <= 0f) return
        // A corner drag follows whichever axis the finger pulled further, so the box keeps up with it.
        val wanted = when {
            cornerX != -1 && cornerY != -1 -> max(abs(nx - anchorX), abs(ny - anchorY) * k)
            cornerX != -1 -> abs(nx - anchorX)
            else -> abs(ny - anchorY) * k
        }
        val width = wanted.coerceIn(max(minWidth, minHeight * k).coerceAtMost(upper), upper)
        val height = width / k
        when (cornerX) {
            0 -> rect.set(anchorX - width, rect.top, anchorX, rect.bottom)
            1 -> rect.set(anchorX, rect.top, anchorX + width, rect.bottom)
            else -> rect.set(anchorX - width / 2f, rect.top, anchorX + width / 2f, rect.bottom)
        }
        when (cornerY) {
            0 -> rect.set(rect.left, anchorY - height, rect.right, anchorY)
            1 -> rect.set(rect.left, anchorY, rect.right, anchorY + height)
            else -> rect.set(rect.left, anchorY - height / 2f, rect.right, anchorY + height / 2f)
        }
    }
}
