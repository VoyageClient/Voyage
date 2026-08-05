/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode.audio

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The two ways of changing speed, told apart by what they do to a tone: resampling moves it, the
 * time stretch leaves it where it was. Both have to land on the requested duration either way.
 */
class PcmSpeedProcessorTest {

    private val sampleRate = 44_100
    private val toneHz = 440.0

    @Test
    fun `changing the pitch speeds the tone up along with the clip`() {
        val output = run(changePitch = true, speed = 2f, seconds = 2.0)

        output.durationSeconds() shouldBeApproximately 1.0
        output.frequency() shouldBeApproximately toneHz * 2
    }

    @Test
    fun `keeping the pitch shortens the clip and leaves the tone alone`() {
        val output = run(changePitch = false, speed = 2f, seconds = 2.0)

        output.durationSeconds() shouldBeApproximately 1.0
        output.frequency() shouldBeApproximately toneHz
    }

    @Test
    fun `slowing down keeps the tone too`() {
        val output = run(changePitch = false, speed = 0.5f, seconds = 1.0)

        output.durationSeconds() shouldBeApproximately 2.0
        output.frequency() shouldBeApproximately toneHz
    }

    @Test
    fun `normal speed is left alone note for note`() {
        val output = run(changePitch = false, speed = 1f, seconds = 1.0)

        output.durationSeconds() shouldBeApproximately 1.0
        output.frequency() shouldBeApproximately toneHz
    }

    @Test
    fun `stereo channels stay in their own lanes`() {
        // Left carries the tone, right is silent. A frame misalignment leaks one into the other.
        val frames = sampleRate
        val input = ShortArray(frames * 2)
        for (frame in 0 until frames) {
            input[frame * 2] = tone(frame)
        }
        val processor = PcmSpeedProcessor(sampleRate, channelCount = 2, changePitch = false)
        processor.speed = 2f
        val output = processor.drainAll(input, frames, channelCount = 2)

        val rightPeak = (1 until output.size step 2).maxOf { abs(output[it].toInt()) }
        (rightPeak < 1_000) shouldBeEqualTo true
    }

    @Test
    fun `a rate change part way through applies from that point on`() {
        // What skipping silence does: half the clip at 1x, half at 4x, so it ends up 5/8 as long.
        val frames = sampleRate
        val input = ShortArray(frames) { tone(it) }
        val processor = PcmSpeedProcessor(sampleRate, channelCount = 1, changePitch = false)
        val output = mutableListOf<Short>()
        processor.speed = 1f
        output += processor.process(input, 0, frames / 2).toList()
        processor.speed = 4f
        output += processor.process(input, frames / 2, frames / 2).toList()
        output += processor.endOfStream().toList()

        (output.size.toDouble() / sampleRate) shouldBeApproximately 0.625
    }

    private fun run(changePitch: Boolean, speed: Float, seconds: Double): ShortArray {
        val frames = (sampleRate * seconds).toInt()
        val input = ShortArray(frames) { tone(it) }
        val processor = PcmSpeedProcessor(sampleRate, channelCount = 1, changePitch = changePitch)
        processor.speed = speed
        return processor.drainAll(input, frames, channelCount = 1)
    }

    /** In buffers, as the exporter feeds it: state carried across calls is where the seams show. */
    private fun PcmSpeedProcessor.drainAll(input: ShortArray, frames: Int, channelCount: Int): ShortArray {
        val output = mutableListOf<Short>()
        var frame = 0
        while (frame < frames) {
            val count = minOf(BUFFER_FRAMES, frames - frame)
            output += process(input, frame * channelCount, count).toList()
            frame += count
        }
        output += endOfStream().toList()
        return output.toShortArray()
    }

    private fun tone(frame: Int) = (sin(2 * PI * toneHz * frame / sampleRate) * Short.MAX_VALUE * 0.8).toInt().toShort()

    private fun ShortArray.durationSeconds() = size.toDouble() / sampleRate

    /** Frequency from zero crossings, which is plenty to tell 440 Hz from 880 Hz. */
    private fun ShortArray.frequency(): Double {
        // The very ends of a stretched buffer can taper; the middle is the steady state.
        val from = size / 4
        val to = size * 3 / 4
        if (to - from < 2) return 0.0
        var crossings = 0
        for (index in from + 1 until to) {
            if (this[index - 1] < 0 && this[index] >= 0) crossings++
        }
        return crossings * sampleRate.toDouble() / (to - from)
    }

    /** Within 5%: neither algorithm lands on an exact sample count, and neither needs to. */
    private infix fun Double.shouldBeApproximately(expected: Double) {
        val within = abs(this - expected) <= expected * 0.05
        if (!within) throw AssertionError("expected about $expected but was ${(this * 1000).roundToInt() / 1000.0}")
    }

    companion object {
        private const val BUFFER_FRAMES = 1024
    }
}
