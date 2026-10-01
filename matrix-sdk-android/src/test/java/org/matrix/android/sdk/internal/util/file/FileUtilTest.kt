/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.util.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileUtilTest {
    @Test
    fun `long names fit both cache file and part file`() {
        val name = "caption".repeat(50) + ".png"
        val safe = safeFileName(name, null)

        assertTrue(safe.endsWith(".png"))
        assertTrue((safe + ".part").toByteArray(Charsets.UTF_8).size <= 255)
        assertEquals(safe, safeFileName(name, null))
        assertNotEquals(safe, safeFileName(name + "extra", null))
    }

    @Test
    fun `multibyte names fit without splitting characters`() {
        val safe = safeFileName("я".repeat(200) + ".png", null)

        assertTrue(safe.endsWith(".png"))
        assertTrue((safe + ".part").toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(!safe.contains('\uFFFD'))
    }
}
