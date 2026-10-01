/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode

import kotlin.math.cos
import kotlin.math.sin

/**
 * A tilt turns the picture clockwise by a small angle about its center. A crop taken from a tilted
 * picture is normalised against the untilted frame, and may run past 0..1 into the corners the turn
 * swings out, as long as it stays on the picture.
 */
object TiltGeometry {

    /**
     * Maps a point of the tilted frame back onto the untilted picture, both normalised and y-down.
     * Pixel sizes are needed because a turn is only a rotation in square units.
     */
    fun untilt(u: Float, v: Float, degrees: Float, width: Float, height: Float): FloatArray {
        if (degrees == 0f || width <= 0f || height <= 0f) return floatArrayOf(u, v)
        val radians = Math.toRadians(degrees.toDouble())
        val c = cos(radians)
        val s = sin(radians)
        val x = (u - 0.5f) * width
        val y = (v - 0.5f) * height
        val unturnedX = x * c + y * s
        val unturnedY = -x * s + y * c
        return floatArrayOf((unturnedX / width + 0.5).toFloat(), (unturnedY / height + 0.5).toFloat())
    }
}
