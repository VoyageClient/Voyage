/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCompressorSavingsTest {

    @Test
    fun `keeps a transcode that shrinks the file a lot`() {
        assertTrue(VideoCompressor.savesEnough(sourceSize = 4_000_000, resultSize = 360_000))
    }

    @Test
    fun `keeps a transcode exactly at the savings threshold`() {
        assertTrue(VideoCompressor.savesEnough(sourceSize = 1_000_000, resultSize = 800_000))
    }

    @Test
    fun `drops a transcode that saves too little`() {
        assertFalse(VideoCompressor.savesEnough(sourceSize = 1_000_000, resultSize = 800_001))
    }

    @Test
    fun `drops a transcode that grew the file`() {
        assertFalse(VideoCompressor.savesEnough(sourceSize = 1_000_000, resultSize = 1_200_000))
    }

    @Test
    fun `keeps any shrink of a file above the trial size`() {
        assertTrue(VideoCompressor.savesEnough(sourceSize = 10_000_000, resultSize = 9_900_000))
    }

    @Test
    fun `drops a transcode that grew a file above the trial size`() {
        assertFalse(VideoCompressor.savesEnough(sourceSize = 10_000_000, resultSize = 10_000_000))
    }
}
