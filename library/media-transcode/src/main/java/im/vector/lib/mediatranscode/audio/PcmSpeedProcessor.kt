/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode.audio

/**
 * Applies a speed to 16-bit interleaved PCM, either dragging the pitch along with it or holding the
 * pitch where it was. Which of the two the caller wants is the "Change pitch" checkbox.
 */
internal class PcmSpeedProcessor(
        sampleRate: Int,
        private val channelCount: Int,
        changePitch: Boolean,
) {

    private val sonic = if (changePitch) null else SonicStream(sampleRate, channelCount)
    private val resampler = if (changePitch) PcmResampler(channelCount) else null

    var speed: Float = 1f
        set(value) {
            field = value
            sonic?.speed = value
            resampler?.speed = value
        }

    fun process(samples: ShortArray, offset: Int, frames: Int): ShortArray {
        resampler?.let { return it.process(samples, offset, frames) }
        val sonic = sonic ?: return ShortArray(0)
        sonic.write(samples, offset, frames)
        return sonic.drain()
    }

    fun endOfStream(): ShortArray {
        resampler?.let {
            it.endOfStream()
            return ShortArray(0)
        }
        val sonic = sonic ?: return ShortArray(0)
        sonic.endOfStream()
        return sonic.drain()
    }

    private fun SonicStream.drain(): ShortArray {
        val output = ShortArray(available * channelCount)
        read(output, available)
        return output
    }
}
