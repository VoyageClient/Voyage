/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test

class MediaCornerRadiusTest {

    @Test
    fun `given media large enough for it, when capping, then the configured radius is kept`() {
        cappedMediaCornerRadius(radius = 21f, width = 756f, height = 567f) shouldBeEqualTo 21f
    }

    @Test
    fun `given media small enough for the full radius to dominate, when capping, then it takes a share of the side`() {
        cappedMediaCornerRadius(radius = 21f, width = 252f, height = 159f) shouldBeEqualTo 159f * MEDIA_CORNER_FRACTION
    }

    @Test
    fun `given a narrow upright image, when capping, then the width is what bounds it`() {
        cappedMediaCornerRadius(radius = 21f, width = 40f, height = 600f) shouldBeEqualTo 40f * MEDIA_CORNER_MAX_FRACTION
    }

    @Test
    fun `given very short media, when capping, then the corner still reads as rounded`() {
        // A proportionate corner would all but vanish here, so it keeps a share of the configured one.
        cappedMediaCornerRadius(radius = 21f, width = 252f, height = 43f) shouldBeEqualTo 21f * 0.4f
    }

    @Test
    fun `given the radius is never more than half a side, when capped, then media cannot come out a pill`() {
        val height = 43f
        val capped = cappedMediaCornerRadius(radius = 1000f, width = 252f, height = height)
        (capped < height / 2f) shouldBeEqualTo true
    }

    @Test
    fun `given an unmeasured view, when capping, then the radius collapses rather than going negative`() {
        cappedMediaCornerRadius(radius = 21f, width = 0f, height = 0f) shouldBeEqualTo 0f
    }
}
