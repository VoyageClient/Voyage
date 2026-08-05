/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode.audio

import kotlin.math.abs

/**
 * Changes the speed of 16-bit interleaved PCM while leaving the pitch alone, by dropping or
 * repeating whole pitch periods and cross-fading over the joins. A port of Bill Cox's Sonic, the
 * algorithm Android's own [android.media.PlaybackParams] time stretch uses; we need it in the
 * exporter because there is no platform API that will time-stretch a buffer.
 *
 * Sample counts here are per channel; the caller's arrays are interleaved.
 */
internal class SonicStream(private val sampleRate: Int, private val channelCount: Int) {

    private val minPeriod = sampleRate / MAX_PITCH_HZ
    private val maxPeriod = sampleRate / MIN_PITCH_HZ
    private val maxRequired = 2 * maxPeriod

    private var input = ShortArray(maxRequired * channelCount * 2)
    private var inputSamples = 0
    private var output = ShortArray(maxRequired * channelCount * 2)
    private var outputSamples = 0
    private var downSampled = ShortArray(maxRequired)

    private var remainingInputToCopy = 0
    private var previousPeriod = 0
    private var previousMinDiff = 0

    var speed = 1f
        set(value) {
            field = value.coerceAtLeast(MIN_SPEED)
        }

    val available get() = outputSamples

    fun write(samples: ShortArray, offset: Int, count: Int) {
        input = input.ensuring((inputSamples + count) * channelCount)
        System.arraycopy(samples, offset, input, inputSamples * channelCount, count * channelCount)
        inputSamples += count
        changeSpeed()
    }

    /** Pushes the tail through, after which [available] holds everything that is left. */
    fun endOfStream() {
        // A final partial window is shorter than the algorithm's lookahead, so pad it with silence
        // and then discard what that padding would have produced.
        val expected = outputSamples + (inputSamples / speed).toInt()
        val padding = maxRequired * 2
        input = input.ensuring((inputSamples + padding) * channelCount)
        java.util.Arrays.fill(input, inputSamples * channelCount, (inputSamples + padding) * channelCount, 0)
        inputSamples += padding
        changeSpeed()
        inputSamples = 0
        remainingInputToCopy = 0
        if (outputSamples > expected) outputSamples = expected
    }

    /** @return the number of samples read into [destination]. */
    fun read(destination: ShortArray, count: Int): Int {
        val taken = minOf(count, outputSamples)
        System.arraycopy(output, 0, destination, 0, taken * channelCount)
        System.arraycopy(output, taken * channelCount, output, 0, (outputSamples - taken) * channelCount)
        outputSamples -= taken
        return taken
    }

    private fun changeSpeed() {
        // Not just an optimisation: the skip and insert formulae both divide by the distance from
        // 1, so at 1 they would ask for an unbounded verbatim copy and never adjust again.
        if (abs(speed - 1f) < SPEED_TOLERANCE) {
            remainingInputToCopy = 0
            growOutput(inputSamples)
            System.arraycopy(input, 0, output, outputSamples * channelCount, inputSamples * channelCount)
            outputSamples += inputSamples
            inputSamples = 0
            return
        }
        if (inputSamples < maxRequired) return
        var position = 0
        do {
            position += if (remainingInputToCopy > 0) {
                copyToOutput(position)
            } else {
                val period = findPitchPeriod(position)
                if (speed > 1f) {
                    period + skipPitchPeriod(position, period)
                } else {
                    insertPitchPeriod(position, period)
                }
            }
        } while (position + maxRequired <= inputSamples)
        System.arraycopy(input, position * channelCount, input, 0, (inputSamples - position) * channelCount)
        inputSamples -= position
    }

    private fun copyToOutput(position: Int): Int {
        val count = minOf(remainingInputToCopy, maxRequired)
        growOutput(count)
        System.arraycopy(input, position * channelCount, output, outputSamples * channelCount, count * channelCount)
        outputSamples += count
        remainingInputToCopy -= count
        return count
    }

    /** Drops one period and cross-fades across the seam. @return the samples written. */
    private fun skipPitchPeriod(position: Int, period: Int): Int {
        val newSamples: Int
        if (speed >= 2f) {
            newSamples = (period / (speed - 1f)).toInt()
        } else {
            newSamples = period
            remainingInputToCopy = (period * (2f - speed) / (speed - 1f)).toInt()
        }
        growOutput(newSamples)
        overlapAdd(newSamples, outputSamples, position, position + period)
        outputSamples += newSamples
        return newSamples
    }

    /** Repeats one period, cross-fading the copy in. @return the samples consumed. */
    private fun insertPitchPeriod(position: Int, period: Int): Int {
        val newSamples: Int
        if (speed < 0.5f) {
            newSamples = (period * speed / (1f - speed)).toInt()
        } else {
            newSamples = period
            remainingInputToCopy = (period * (2f * speed - 1f) / (1f - speed)).toInt()
        }
        growOutput(period + newSamples)
        System.arraycopy(input, position * channelCount, output, outputSamples * channelCount, period * channelCount)
        overlapAdd(newSamples, outputSamples + period, position + period, position)
        outputSamples += period + newSamples
        return newSamples
    }

    private fun overlapAdd(count: Int, outputAt: Int, rampDown: Int, rampUp: Int) {
        if (count <= 0) return
        for (channel in 0 until channelCount) {
            var out = outputAt * channelCount + channel
            var down = rampDown * channelCount + channel
            var up = rampUp * channelCount + channel
            for (index in 0 until count) {
                output[out] = ((input[down] * (count - index) + input[up] * index) / count).toShort()
                out += channelCount
                down += channelCount
                up += channelCount
            }
        }
    }

    /**
     * The dominant period under the read head, by average magnitude difference. Searched on a
     * mono, decimated copy first — full rate over the whole range is far too slow on the phones
     * this fork targets — then refined around the winner.
     */
    private fun findPitchPeriod(position: Int): Int {
        val skip = if (sampleRate > AMDF_FREQ_HZ) sampleRate / AMDF_FREQ_HZ else 1
        downSample(position, skip)
        var period = findPitchPeriodInRange(minPeriod / skip, maxPeriod / skip)
        if (skip != 1) {
            period *= skip
            val low = (period - (skip shl 2)).coerceAtLeast(minPeriod)
            val high = (period + (skip shl 2)).coerceAtMost(maxPeriod)
            downSample(position, 1)
            period = findPitchPeriodInRange(low, high)
        }
        // An octave error is audible as a stutter, so keep the previous period unless this one is
        // both a good match in its own right and not much worse than the one before.
        val keepPrevious = minDiff != 0 && previousPeriod != 0 &&
                maxDiff <= minDiff * 3 && minDiff * 2 > previousMinDiff * 3
        val result = if (keepPrevious) previousPeriod else period
        previousMinDiff = minDiff
        previousPeriod = period
        return result
    }

    private var minDiff = 0
    private var maxDiff = 0

    private fun findPitchPeriodInRange(low: Int, high: Int): Int {
        var bestPeriod = 0
        var bestDiff = 1L
        var worstPeriod = 0
        var worstDiff = 1L
        for (period in low..high) {
            var diff = 0L
            for (index in 0 until period) {
                diff += abs(downSampled[index] - downSampled[index + period])
            }
            if (bestPeriod == 0 || diff * bestPeriod < bestDiff * period) {
                bestDiff = diff
                bestPeriod = period
            }
            if (worstPeriod == 0 || diff * worstPeriod > worstDiff * period) {
                worstDiff = diff
                worstPeriod = period
            }
        }
        minDiff = (bestDiff / bestPeriod.coerceAtLeast(1)).toInt()
        maxDiff = (worstDiff / worstPeriod.coerceAtLeast(1)).toInt()
        return bestPeriod.coerceAtLeast(low)
    }

    private fun downSample(position: Int, skip: Int) {
        val count = maxRequired / skip
        val perValue = channelCount * skip
        downSampled = downSampled.ensuring(count)
        var read = position * channelCount
        for (index in 0 until count) {
            var value = 0
            for (offset in 0 until perValue) {
                value += input[read++]
            }
            downSampled[index] = (value / perValue).toShort()
        }
    }

    private fun growOutput(extra: Int) {
        output = output.ensuring((outputSamples + extra) * channelCount)
    }

    private fun ShortArray.ensuring(capacity: Int): ShortArray =
            if (size >= capacity) this else copyOf(maxOf(capacity, size * 2))

    companion object {
        private const val MIN_PITCH_HZ = 65
        private const val MAX_PITCH_HZ = 400
        private const val AMDF_FREQ_HZ = 4000
        private const val MIN_SPEED = 0.01f
        private const val SPEED_TOLERANCE = 0.001f
    }
}
