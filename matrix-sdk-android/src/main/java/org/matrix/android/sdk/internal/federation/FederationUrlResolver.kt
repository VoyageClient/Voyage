/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.federation

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.CancellationException
import org.matrix.android.sdk.api.federation.ServerName
import org.matrix.android.sdk.internal.network.executeRequest
import timber.log.Timber
import javax.inject.Inject

/**
 * Server discovery from the server-server spec: explicit port, then `.well-known/matrix/server` delegation,
 * then port 8448. SRV records are skipped: Android has no DNS SRV API, and nearly every server delegates by well-known.
 */
internal class FederationUrlResolver @Inject constructor(
        private val federationAPI: FederationAPI,
) {

    suspend fun resolve(serverName: String): String {
        if (ServerName.hasExplicitPort(serverName)) return "https://$serverName"
        val host = ServerName.hostOf(serverName)
        if (!ServerName.isIpLiteral(host)) {
            fetchDelegation(host)?.let { delegated ->
                return if (ServerName.hasExplicitPort(delegated)) "https://$delegated" else "https://$delegated:${ServerName.DEFAULT_FEDERATION_PORT}"
            }
        }
        return "https://$serverName:${ServerName.DEFAULT_FEDERATION_PORT}"
    }

    private suspend fun fetchDelegation(host: String): String? {
        return try {
            executeRequest(null) { federationAPI.getServerWellKnown("https://$host/.well-known/matrix/server") }
                    .server
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && '/' !in it }
        } catch (e: CancellationException) {
            throw e
        } catch (failure: Throwable) {
            Timber.d("No federation delegation for $host: ${failure.message}")
            null
        }
    }
}

@JsonClass(generateAdapter = true)
internal data class ServerWellKnown(
        @Json(name = "m.server")
        val server: String?
)
