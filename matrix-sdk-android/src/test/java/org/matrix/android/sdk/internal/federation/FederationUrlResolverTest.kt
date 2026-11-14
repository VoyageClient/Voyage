/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.federation

import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Test
import java.io.IOException

class FederationUrlResolverTest {

    private class FakeFederationAPI(private val wellKnown: Map<String, String?>) : FederationAPI {
        val requested = mutableListOf<String>()

        override suspend fun getVersion(url: String): FederationGetVersionResult = throw UnsupportedOperationException()

        override suspend fun getServerWellKnown(url: String): ServerWellKnown {
            requested += url
            if (url !in wellKnown) throw IOException("404")
            return ServerWellKnown(wellKnown[url])
        }
    }

    private fun resolve(serverName: String, api: FakeFederationAPI) = runBlocking { FederationUrlResolver(api).resolve(serverName) }

    @Test
    fun `delegation without a port defaults to 8448`() {
        val api = FakeFederationAPI(mapOf("https://example.org/.well-known/matrix/server" to "matrix.example.org"))
        resolve("example.org", api) shouldBeEqualTo "https://matrix.example.org:8448"
    }

    @Test
    fun `delegation keeps its explicit port`() {
        val api = FakeFederationAPI(mapOf("https://example.org/.well-known/matrix/server" to "matrix.example.org:443"))
        resolve("example.org", api) shouldBeEqualTo "https://matrix.example.org:443"
    }

    @Test
    fun `missing or invalid delegation falls back to the server name on 8448`() {
        resolve("example.org", FakeFederationAPI(emptyMap())) shouldBeEqualTo "https://example.org:8448"
        val blank = FakeFederationAPI(mapOf("https://example.org/.well-known/matrix/server" to " "))
        resolve("example.org", blank) shouldBeEqualTo "https://example.org:8448"
    }

    @Test
    fun `explicit port and IP literals skip the well-known lookup`() {
        val api = FakeFederationAPI(emptyMap())
        resolve("example.org:8008", api) shouldBeEqualTo "https://example.org:8008"
        resolve("192.0.2.1", api) shouldBeEqualTo "https://192.0.2.1:8448"
        resolve("[2001:db8::1]", api) shouldBeEqualTo "https://[2001:db8::1]:8448"
        api.requested.shouldBeEmpty()
    }
}
