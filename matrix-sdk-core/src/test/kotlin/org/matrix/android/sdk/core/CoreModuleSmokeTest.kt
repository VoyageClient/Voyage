/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.core

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Test
import org.matrix.android.sdk.api.auth.data.WellKnownBaseConfig
import org.matrix.android.sdk.api.util.fromBase64
import org.matrix.android.sdk.api.util.toBase64NoPadding

/**
 * Smoke of the core-gate mechanism itself: the shared sources compiled here run on a plain JVM,
 * including a kapt-generated Moshi adapter (looked up reflectively at runtime, so compiling alone
 * would not prove codegen ran).
 */
class CoreModuleSmokeTest {

    @Test
    fun `generated moshi adapter round-trips on plain jvm`() {
        val adapter = Moshi.Builder().build().adapter(WellKnownBaseConfig::class.java)

        val parsed = adapter.fromJson("""{"base_url":"https://vector.im"}""")!!
        assertEquals("https://vector.im", parsed.baseURL)
        assertEquals(parsed, adapter.fromJson(adapter.toJson(parsed)))
    }

    @Test
    fun `shared base64 helpers run on plain jvm`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        assertEquals("AQIDBAU", data.toBase64NoPadding())
        assertEquals(data.toList(), "AQIDBAU".fromBase64().toList())
    }
}
