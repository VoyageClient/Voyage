/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A full rotation, split into the quarter turns that decide the frame's shape and the tilt within
 * ±45° that turns the picture inside it.
 */
data class RotationSplit(val quarterTurns: Int, val tiltDegrees: Float) {
    companion object {
        fun of(degrees: Float): RotationSplit {
            val normalised = AngleSnap.normalise(degrees)
            val nearestQuarter = (normalised / 90f).roundToInt() * 90
            return RotationSplit(nearestQuarter % 360, normalised - nearestQuarter)
        }
    }
}

object AngleSnap {
    private const val STEP_DEGREES = 45f
    private const val DISTANCE_DEGREES = 5f

    fun normalise(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f

    /** Normalises [degrees] to [0, 360) and, when [enabled], pulls it onto a nearby 45° multiple. */
    fun snap(degrees: Float, enabled: Boolean): Float {
        val normalised = normalise(degrees)
        if (!enabled) return normalised
        val nearest = (normalised / STEP_DEGREES).roundToInt() * STEP_DEGREES
        return if (abs(normalised - nearest) <= DISTANCE_DEGREES) normalise(nearest) else normalised
    }

    fun isSnapped(degrees: Float) = normalise(degrees) % STEP_DEGREES == 0f
}

/** The 90° buttons step to the neighbouring multiple of 90°, so a tilted picture is straightened. */
object QuarterStep {
    private const val EPSILON = 0.01f

    fun isQuarter(degrees: Float) = abs(degrees - (degrees / 90f).roundToInt() * 90f) < EPSILON

    fun next(degrees: Float): Float = AngleSnap.normalise(
            if (isQuarter(degrees)) (degrees / 90f).roundToInt() * 90f + 90f else ceil(degrees / 90f) * 90f
    )

    fun previous(degrees: Float): Float = AngleSnap.normalise(
            if (isQuarter(degrees)) (degrees / 90f).roundToInt() * 90f - 90f else floor(degrees / 90f) * 90f
    )
}

/** Follows the angle between the first two pointers, so two fingers can turn the picture. */
class TwistTracker {
    private var lastAngle = 0f

    fun begin(event: MotionEvent) {
        lastAngle = angleOf(event)
    }

    /** Clockwise degrees turned since the last call. */
    fun delta(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val angle = angleOf(event)
        var delta = angle - lastAngle
        lastAngle = angle
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }

    private fun angleOf(event: MotionEvent): Float {
        if (event.pointerCount < 2) return lastAngle
        // y runs downwards, so a growing angle is a clockwise turn on screen.
        return Math.toDegrees(atan2((event.getY(1) - event.getY(0)).toDouble(), (event.getX(1) - event.getX(0)).toDouble())).toFloat()
    }
}
