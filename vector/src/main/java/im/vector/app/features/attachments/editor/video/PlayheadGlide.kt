/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

/**
 * Carries the playhead between playback reports, so everything showing it moves continuously rather
 * than stepping once per report.
 *
 * A report arrives every [intervalMs] of wall time, which at the clip's speed is that much times the
 * speed in clip time — so the glide aims at where playback will be when the next one lands, and a jump
 * (a seek, a loop back to the start, a trim) snaps instead.
 */
object PlayheadGlide {

    /** Whether moving from [fromUs] to [toUs] is playback advancing, rather than a jump to snap to. */
    fun glides(fromUs: Long, toUs: Long, intervalMs: Long, speed: Float, maxStepUs: Long): Boolean {
        val delta = toUs - fromUs
        val ahead = aheadUs(intervalMs, speed)
        // A glide already aims a report ahead, so a report arriving where playback truly is reads as a
        // step backwards of that much — and it grows with the speed, which is why a stall snapped the
        // playhead back only on a sped-up clip. Within a report of that, playback is still advancing.
        return delta >= -(ahead + intervalMs * 1000) && delta <= maxStepUs.coerceAtLeast(ahead)
    }

    /** How far the clip travels in one report's worth of wall time. */
    fun aheadUs(intervalMs: Long, speed: Float): Long = (intervalMs * 1000L * speed.coerceAtLeast(0.01f)).toLong()

    /**
     * Where to aim a glide that starts at a report of [toUs]: never past the cut at [limitUs], and never
     * behind [notBeforeUs] — where the playhead already is, so a position that stalls or wobbles holds
     * the playhead there instead of walking it back over clip it has already covered.
     */
    fun aimUs(toUs: Long, limitUs: Long, intervalMs: Long, speed: Float, notBeforeUs: Long = 0): Long {
        val aim = maxOf(toUs + aheadUs(intervalMs, speed), notBeforeUs)
        return if (limitUs > 0) aim.coerceAtMost(limitUs) else aim
    }
}
