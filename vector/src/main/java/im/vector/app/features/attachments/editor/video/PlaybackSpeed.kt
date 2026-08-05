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
 * Speed of the exported clip, modelled on NewPipe's playback parameter dialog.
 *
 * @property speed 1 leaves the timing alone; below 1 is slower, above 1 faster.
 * @property changePitch whether the audio pitch rides along with the speed, as tape does. Turning
 * it off keeps the original pitch, which needs a time stretch rather than a resample. Meaningless
 * for animated images, which carry no audio.
 */
@Parcelize
data class PlaybackSpeed(
        val speed: Float = NORMAL,
        val changePitch: Boolean = true,
) : Parcelable {

    val isDefault: Boolean get() = abs(speed - NORMAL) < TOLERANCE

    companion object {
        const val NORMAL = 1f
        const val MINIMUM = 0.1f
        const val MAXIMUM = 3f
        private const val TOLERANCE = 0.001f

        /** The steps the plus/minus buttons take, as percentages. */
        val STEP_PERCENTAGES = listOf(1, 5, 10, 25, 100)
    }
}
