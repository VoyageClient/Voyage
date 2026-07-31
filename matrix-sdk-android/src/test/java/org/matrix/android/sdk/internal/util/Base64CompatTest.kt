/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.util.fromBase64
import org.matrix.android.sdk.api.util.toBase64NoPadding
import org.matrix.android.sdk.internal.crypto.MXMegolmExportEncryption
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * Pins the java.util.Base64-based replacements against the real android.util.Base64 (provided by
 * Robolectric) so encodings that reach the wire, exports or persisted storage stay byte-identical
 * after the desktop-port migration off the Android framework.
 */
@RunWith(RobolectricTestRunner::class)
class Base64CompatTest {

    private val samples = (0..96).map { Random(it.toLong()).nextBytes(it) }

    @Test
    fun `toBase64NoPadding matches android NO_PADDING or NO_WRAP`() {
        samples.forEach { data ->
            assertEquals(
                    android.util.Base64.encodeToString(data, android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP),
                    data.toBase64NoPadding(),
            )
        }
    }

    @Test
    fun `java plain encoder matches android DEFAULT after newline strip for short input`() {
        samples.filter { it.size <= 48 }.forEach { data ->
            assertEquals(
                    android.util.Base64.encodeToString(data, android.util.Base64.DEFAULT).replace("\n", ""),
                    java.util.Base64.getEncoder().encodeToString(data),
            )
        }
    }

    @Test
    fun `megolm export chunk encoding matches android DEFAULT byte for byte`() {
        samples.forEach { data ->
            assertArrayEquals(
                    "size=${data.size}",
                    android.util.Base64.encode(data, android.util.Base64.DEFAULT),
                    MXMegolmExportEncryption.encodeLikeAndroidDefault(data),
            )
        }
    }

    @Test
    fun `fromBase64 decodes everything android DEFAULT encoding produced`() {
        samples.forEach { data ->
            assertArrayEquals(data, android.util.Base64.encodeToString(data, android.util.Base64.DEFAULT).fromBase64())
            assertArrayEquals(data, android.util.Base64.encodeToString(data, android.util.Base64.NO_PADDING).fromBase64())
            assertArrayEquals(data, data.toBase64NoPadding().fromBase64())
        }
    }
}
