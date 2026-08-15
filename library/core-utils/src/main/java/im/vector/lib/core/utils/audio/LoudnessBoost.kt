/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.core.utils.audio

import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlin.math.log10

/**
 * Plays audio louder than its own samples, which a player's 0..1 volume scalar cannot do. Kept in
 * its own class so nothing below KitKat ever verifies a method mentioning [LoudnessEnhancer].
 *
 * It is a compressor rather than a multiplier, so it gives a fair impression of the gain rather
 * than a measurement of it.
 */
@RequiresApi(Build.VERSION_CODES.KITKAT)
class LoudnessBoost private constructor(private val enhancer: LoudnessEnhancer) {

    fun setGain(gain: Float) {
        val millibels = if (gain <= 1f) 0 else (MILLIBELS_PER_DECADE * log10(gain.toDouble())).toInt()
        runCatching {
            enhancer.setTargetGain(millibels)
            enhancer.enabled = millibels > 0
        }.onFailure { Log.w(TAG, "Cannot boost to ${millibels}mB", it) }
    }

    /** An effect outlives the session it was attached to, and a leaked one keeps boosting. */
    fun release() {
        runCatching { enhancer.release() }
    }

    companion object {
        private val TAG = LoudnessBoost::class.java.name

        /** LoudnessEnhancer takes hundredths of a dB, and a decade of amplitude is 20dB. */
        private const val MILLIBELS_PER_DECADE = 2000

        /** @return null when the device has no such effect to offer. */
        fun attachTo(audioSessionId: Int): LoudnessBoost? = runCatching { LoudnessEnhancer(audioSessionId) }
                .onFailure { Log.w(TAG, "Not available on this device", it) }
                .getOrNull()
                ?.let { LoudnessBoost(it) }
    }
}
