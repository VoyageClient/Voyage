/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlin.math.abs

/**
 * Loudness of the exported clip.
 *
 * @property gain 1 leaves the sound alone; above 1 amplifies it, at the cost of clipping whatever
 * was already near full scale.
 * @property muted drops the audio track entirely, which the gain no longer has any say over.
 */
@Parcelize
data class PlaybackVolume(
        val gain: Float = NORMAL,
        val muted: Boolean = false,
) : Parcelable {

    val isDefault: Boolean get() = !muted && abs(gain - NORMAL) < TOLERANCE

    /** What the audio is actually scaled by, so a muted clip reads as silent everywhere. */
    val effectiveGain: Float get() = if (muted) 0f else gain

    companion object {
        const val NORMAL = 1f
        const val MINIMUM = 0f
        const val MAXIMUM = 5f
        private const val TOLERANCE = 0.001f

        /** The steps the plus/minus buttons take, as percentages. */
        val STEP_PERCENTAGES = listOf(1, 5, 10, 25, 100)
    }
}
