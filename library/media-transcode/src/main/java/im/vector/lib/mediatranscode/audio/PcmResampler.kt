/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode.audio

/**
 * Speeds 16-bit interleaved PCM up or down by resampling it, which drags the pitch along as a tape
 * does. The pitch-preserving alternative is [SonicStream].
 *
 * The read position is fractional and carries across calls, so a rate that does not divide the
 * buffer length evenly still produces a seamless stream rather than a click per buffer.
 */
internal class PcmResampler(private val channelCount: Int) {

    private var carry = ShortArray(0)
    private var position = 0.0

    var speed = 1f
        set(value) {
            field = value.coerceAtLeast(MIN_SPEED)
        }

    fun process(samples: ShortArray, offset: Int, count: Int): ShortArray {
        // Interpolation reads one frame past the position, so the previous buffer's last frame has
        // to still be there when the position lands between the two.
        val input = ShortArray(carry.size + count * channelCount)
        System.arraycopy(carry, 0, input, 0, carry.size)
        System.arraycopy(samples, offset, input, carry.size, count * channelCount)
        val frames = input.size / channelCount

        val output = ShortArray(((frames - position) / speed).toInt().coerceAtLeast(0) * channelCount)
        var written = 0
        while (position < frames - 1 && written + channelCount <= output.size) {
            val index = position.toInt()
            val fraction = position - index
            for (channel in 0 until channelCount) {
                val low = input[index * channelCount + channel]
                val high = input[(index + 1) * channelCount + channel]
                output[written++] = (low + (high - low) * fraction).toInt().toShort()
            }
            position += speed
        }

        val consumed = position.toInt()
        carry = input.copyOfRange(consumed * channelCount, input.size)
        position -= consumed
        return if (written == output.size) output else output.copyOf(written)
    }

    /** Drops the frame held back for interpolation — 20-odd microseconds, at the very end. */
    fun endOfStream() {
        carry = ShortArray(0)
        position = 0.0
    }

    companion object {
        private const val MIN_SPEED = 0.01f
    }
}
