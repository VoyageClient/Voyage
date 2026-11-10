/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.voice

import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInRange
import org.junit.Test

class AudioWaveformNormalizeTest {

    @Test
    fun `the loudest sample reaches the scale and quiet ones are raised`() {
        val waveform = AudioWaveformView.normalize(listOf(0, 1_000, 4_000, 16_000), scale = 1024)

        waveform shouldBeEqualTo listOf(0, 256, 512, 1024)
    }

    @Test
    fun `a restored waveform normalizes back to itself`() {
        val saved = listOf(0, 120, 300, 512, 800, 1024)

        val restored = AudioWaveformView.normalize(AudioWaveformView.denormalize(saved, scale = 1024), scale = 1024)

        restored.zip(saved).forEach { (again, original) -> again shouldBeInRange (original - 1)..original }
    }

    @Test
    fun `silence is left alone`() {
        AudioWaveformView.normalize(listOf(0, 0, 0), scale = 1024) shouldBeEqualTo listOf(0, 0, 0)
    }
}
