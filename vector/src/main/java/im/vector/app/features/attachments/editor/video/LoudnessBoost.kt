/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.editor.video

import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import androidx.annotation.RequiresApi
import timber.log.Timber
import kotlin.math.log10

/**
 * Plays a clip louder than its own samples, which MediaPlayer's 0..1 scalar cannot do. Kept in its
 * own class so nothing below KitKat ever verifies a method mentioning [LoudnessEnhancer].
 *
 * It is a compressor rather than a multiplier, so it gives a fair impression of the exported boost
 * rather than a measurement of it.
 */
@RequiresApi(Build.VERSION_CODES.KITKAT)
class LoudnessBoost private constructor(private val enhancer: LoudnessEnhancer) {

    fun setGain(gain: Float) {
        val millibels = if (gain <= 1f) 0 else (MILLIBELS_PER_DECADE * log10(gain.toDouble())).toInt()
        runCatching {
            enhancer.setTargetGain(millibels)
            enhancer.enabled = millibels > 0
        }.onFailure { Timber.w(it, "VideoEditor: cannot boost to ${millibels}mB") }
    }

    /** An effect outlives the session it was attached to, and a leaked one keeps boosting. */
    fun release() {
        runCatching { enhancer.release() }
    }

    companion object {
        /** LoudnessEnhancer takes hundredths of a dB, and a decade of amplitude is 20dB. */
        private const val MILLIBELS_PER_DECADE = 2000

        /** @return null when the device has no such effect to offer. */
        fun attachTo(player: MediaPlayer): LoudnessBoost? = runCatching { LoudnessEnhancer(player.audioSessionId) }
                .onFailure { Timber.w(it, "VideoEditor: no loudness boost on this device") }
                .getOrNull()
                ?.let { LoudnessBoost(it) }
    }
}
