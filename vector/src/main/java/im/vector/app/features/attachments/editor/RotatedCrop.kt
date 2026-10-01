/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The maths of a crop taken from a picture turned by any angle. Rectangles here are in picture
 * pixels, upright on screen, relative to the picture's center: the picture turns underneath the crop,
 * and a crop is valid wherever all four of its corners land on the picture.
 */
class RotatedCrop(private val width: Float, private val height: Float, degrees: Float) {

    private val radians = Math.toRadians(degrees.toDouble())
    private val cos = cos(radians).toFloat()
    private val sin = sin(radians).toFloat()

    /** The upright box the turned picture fills. */
    val boundsWidth = abs(width * cos) + abs(height * sin)
    val boundsHeight = abs(width * sin) + abs(height * cos)

    fun contains(x: Float, y: Float): Boolean {
        // Turned back by the angle, the point has to land on the unturned picture.
        val unturnedX = x * cos + y * sin
        val unturnedY = -x * sin + y * cos
        return abs(unturnedX) <= width / 2f + TOLERANCE && abs(unturnedY) <= height / 2f + TOLERANCE
    }

    fun fits(rect: RectF) = contains(rect.left, rect.top) && contains(rect.right, rect.top) &&
            contains(rect.left, rect.bottom) && contains(rect.right, rect.bottom)

    /** How much the picture has to grow about its center for [rect] to fit; 1 when it already does. */
    fun requiredScale(rect: RectF): Float {
        var scale = 1f
        for (x in floatArrayOf(rect.left, rect.right)) {
            for (y in floatArrayOf(rect.top, rect.bottom)) {
                val unturnedX = x * cos + y * sin
                val unturnedY = -x * sin + y * cos
                scale = maxOf(scale, abs(unturnedX) * 2f / width, abs(unturnedY) * 2f / height)
            }
        }
        return scale
    }

    /** The furthest point from [from] (which fits) toward [to] that still fits. */
    fun reachable(from: RectF, to: RectF): RectF {
        if (fits(to)) return RectF(to)
        var fitting = 0f
        var failing = 1f
        repeat(BISECTION_STEPS) {
            val middle = (fitting + failing) / 2f
            if (fits(lerp(from, to, middle))) fitting = middle else failing = middle
        }
        return lerp(from, to, fitting)
    }

    fun toBoundsNormalised(rect: RectF) = RectF(
            rect.left / boundsWidth + 0.5f,
            rect.top / boundsHeight + 0.5f,
            rect.right / boundsWidth + 0.5f,
            rect.bottom / boundsHeight + 0.5f,
    )

    fun fromBoundsNormalised(rect: RectF) = RectF(
            (rect.left - 0.5f) * boundsWidth,
            (rect.top - 0.5f) * boundsHeight,
            (rect.right - 0.5f) * boundsWidth,
            (rect.bottom - 0.5f) * boundsHeight,
    )

    companion object {
        private const val TOLERANCE = 0.5f
        private const val BISECTION_STEPS = 14

        /** A frame's size once turned by [quarterTurns]. */
        fun frameSize(width: Float, height: Float, quarterTurns: Int) =
                if (quarterTurns % 180 != 0) height to width else width to height

        /** Normalised against the picture's frame at [quarterTurns]; may run past 0..1 when tilted. */
        fun toFrameNormalised(rect: RectF, width: Float, height: Float, quarterTurns: Int): RectF {
            val (frameWidth, frameHeight) = frameSize(width, height, quarterTurns)
            return RectF(
                    rect.left / frameWidth + 0.5f,
                    rect.top / frameHeight + 0.5f,
                    rect.right / frameWidth + 0.5f,
                    rect.bottom / frameHeight + 0.5f,
            )
        }

        fun fromFrameNormalised(rect: RectF, width: Float, height: Float, quarterTurns: Int): RectF {
            val (frameWidth, frameHeight) = frameSize(width, height, quarterTurns)
            return RectF(
                    (rect.left - 0.5f) * frameWidth,
                    (rect.top - 0.5f) * frameHeight,
                    (rect.right - 0.5f) * frameWidth,
                    (rect.bottom - 0.5f) * frameHeight,
            )
        }

        /** The same region after the picture turns a quarter clockwise underneath it. */
        fun turnedClockwise(rect: RectF) = RectF(-rect.bottom, rect.left, -rect.top, rect.right)

        private fun lerp(from: RectF, to: RectF, t: Float) = RectF(
                from.left + (to.left - from.left) * t,
                from.top + (to.top - from.top) * t,
                from.right + (to.right - from.right) * t,
                from.bottom + (to.bottom - from.bottom) * t,
        )
    }
}
