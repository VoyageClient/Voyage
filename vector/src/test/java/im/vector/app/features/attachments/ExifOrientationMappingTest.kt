/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import org.matrix.android.sdk.api.session.content.ContentAttachmentData

class ExifOrientationMappingTest {

    private fun swapsSides(rotationDegrees: Int) =
            exifOrientationOf(rotationDegrees) in ContentAttachmentData.EXIF_ORIENTATIONS_SWAPPING_SIZE

    @Test
    fun `a quarter turn swaps the sides`() {
        swapsSides(90) shouldBeEqualTo true
        swapsSides(270) shouldBeEqualTo true
    }

    @Test
    fun `an upright or upside-down picture keeps its sides`() {
        swapsSides(0) shouldBeEqualTo false
        swapsSides(180) shouldBeEqualTo false
    }

    @Test
    fun `an unreadable rotation is treated as upright`() {
        exifOrientationOf(-1) shouldBeEqualTo 1
    }
}
