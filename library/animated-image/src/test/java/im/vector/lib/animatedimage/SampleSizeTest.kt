/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.animatedimage

import org.junit.Assert.assertEquals
import org.junit.Test

class SampleSizeTest {

    @Test
    fun `no downscale samples every pixel`() {
        assertEquals(1, sampleSizeFor(1f))
        assertEquals(1, sampleSizeFor(1.5f))
        assertEquals(1, sampleSizeFor(0f))
    }

    @Test
    fun `sample size never shrinks below the requested scale`() {
        assertEquals(1, sampleSizeFor(0.75f))
        assertEquals(2, sampleSizeFor(0.5f))
        assertEquals(2, sampleSizeFor(0.3251177f))
        assertEquals(4, sampleSizeFor(0.25f))
        assertEquals(4, sampleSizeFor(0.13f))
        for (scale in listOf(0.9f, 0.6f, 0.33f, 0.2f, 0.07f, 0.01f)) {
            assert(1f / sampleSizeFor(scale) >= scale) { "scale $scale" }
        }
    }
}
