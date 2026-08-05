/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentAttachmentDataTest {

    private val attachment = ContentAttachmentData(
            size = 42,
            name = "picture.png",
            queryUri = "content://media/external/images/media/1",
            mimeType = "image/png",
            type = ContentAttachmentData.Type.IMAGE,
    )

    /**
     * The JSON shape must stay identical to when queryUri was android.net.Uri (which serialized as
     * the plain string): pending uploads persisted before the type change must keep loading.
     */
    @Test
    fun `json round-trips and queryUri keeps the legacy plain-string shape`() {
        val json = attachment.toJsonString()
        assertTrue(json, json.contains(""""queryUri":"content://media/external/images/media/1""""))
        assertEquals(attachment, ContentAttachmentData.fromJsonString(json))
    }

    private val landscapeFile = attachment.copy(width = 4000, height = 3000)

    @Test
    fun `an unrotated photo is displayed the way it is stored`() {
        assertEquals(4000L, landscapeFile.displayWidth)
        assertEquals(3000L, landscapeFile.displayHeight)
    }

    /**
     * What the sender is offered in the compression sheet, and what any size they type is measured
     * against — the compressor rotates before it scales, so a size in stored terms comes out squashed.
     */
    @Test
    fun `a quarter turn swaps the sides`() {
        // TRANSPOSE, ROTATE_90, TRANSVERSE, ROTATE_270: a 4000x3000 file is a 3000x4000 picture.
        listOf(5, 6, 7, 8).forEach { orientation ->
            val rotated = landscapeFile.copy(exifOrientation = orientation)
            assertEquals("orientation $orientation", 3000L, rotated.displayWidth)
            assertEquals("orientation $orientation", 4000L, rotated.displayHeight)
        }
    }

    @Test
    fun `a half turn or a mirror leaves the sides alone`() {
        listOf(1, 2, 3, 4).forEach { orientation ->
            val rotated = landscapeFile.copy(exifOrientation = orientation)
            assertEquals("orientation $orientation", 4000L, rotated.displayWidth)
            assertEquals("orientation $orientation", 3000L, rotated.displayHeight)
        }
    }

    @Test
    fun `a chosen size wins over the source and is already the right way round`() {
        val resized = landscapeFile.copy(exifOrientation = 6, compressionWidth = 1500, compressionHeight = 2000)

        assertEquals(1500L, resized.outputWidth)
        assertEquals(2000L, resized.outputHeight)
    }

    @Test
    fun `only a real choice counts as custom compression`() {
        assertEquals(false, landscapeFile.hasCustomCompression)
        assertEquals(true, landscapeFile.copy(compressionQuality = 40).hasCustomCompression)
        assertEquals(true, landscapeFile.copy(compressionWidth = 800, compressionHeight = 600).hasCustomCompression)
    }
}
