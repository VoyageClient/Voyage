/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.auth.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.matrix.android.sdk.internal.di.MoshiProvider

class HomeServerConnectionConfigTest {

    @Test
    fun `builder accepts http and https and ensures a trailing slash`() {
        val config = HomeServerConnectionConfig.Builder()
                .withHomeServerUri("https://example.org")
                .withIdentityServerUri("http://identity.example.org/")
                .build()
        assertEquals("https://example.org/", config.homeServerUri)
        assertEquals("https://example.org/", config.homeServerUriBase)
        assertEquals("http://identity.example.org/", config.identityServerUri)
    }

    @Test
    fun `builder rejects URIs without an http scheme`() {
        assertThrows(RuntimeException::class.java) {
            HomeServerConnectionConfig.Builder().withHomeServerUri("example.org")
        }
        assertThrows(RuntimeException::class.java) {
            HomeServerConnectionConfig.Builder().withHomeServerUri("ftp://example.org")
        }
        assertThrows(RuntimeException::class.java) {
            HomeServerConnectionConfig.Builder().withIdentityServerUri("identity.example.org")
        }
        assertThrows(RuntimeException::class.java) {
            HomeServerConnectionConfig.Builder().withAntiVirusServerUri("av.example.org")
        }
    }

    /**
     * The JSON shape must stay identical to when the fields were android.net.Uri (which serialized
     * as the plain string): persisted SessionParams from before the type change must keep loading.
     */
    @Test
    fun `json round-trips and keeps the legacy string shape`() {
        val adapter = MoshiProvider.providesMoshi().adapter(HomeServerConnectionConfig::class.java)
        val legacyJson = """{"homeServerUri":"https://example.org/","homeServerUriBase":"https://matrix.example.org/","identityServerUri":"https://id.example.org/"}"""

        val parsed = adapter.fromJson(legacyJson)!!
        assertEquals("https://example.org/", parsed.homeServerUri)
        assertEquals("https://matrix.example.org/", parsed.homeServerUriBase)
        assertEquals("https://id.example.org/", parsed.identityServerUri)

        assertEquals(parsed, adapter.fromJson(adapter.toJson(parsed)))
    }
}
