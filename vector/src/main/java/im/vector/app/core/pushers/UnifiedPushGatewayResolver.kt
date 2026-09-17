/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.pushers

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.Matrix
import org.matrix.android.sdk.api.cache.CacheStrategy
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.util.MatrixJsonParser
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

sealed interface UnifiedPushGatewayResolverResult {
    /** The endpoint's host serves a Matrix gateway of its own. */
    data class Success(val gateway: String) : UnifiedPushGatewayResolverResult

    /** The host answered, but not as a Matrix gateway. */
    object NoMatrixGateway : UnifiedPushGatewayResolverResult

    /** The probe itself failed, so we learned nothing about the host. */
    data class Error(val gateway: String) : UnifiedPushGatewayResolverResult

    object ErrorInvalidUrl : UnifiedPushGatewayResolverResult
}

class UnifiedPushGatewayResolver @Inject constructor(
        private val matrix: Matrix,
) {

    @JsonClass(generateAdapter = true)
    internal data class DiscoveryResponse(
            @Json(name = "unifiedpush") val unifiedpush: DiscoveryUnifiedPush = DiscoveryUnifiedPush()
    )

    @JsonClass(generateAdapter = true)
    internal data class DiscoveryUnifiedPush(
            @Json(name = "gateway") val gateway: String = ""
    )

    suspend fun getGateway(endpoint: String): UnifiedPushGatewayResolverResult {
        val url = tryOrNull { URL(endpoint) } ?: return UnifiedPushGatewayResolverResult.ErrorInvalidUrl
        val port = if (url.port != -1) ":${url.port}" else ""
        return probe("${url.protocol}://${url.host}$port$NOTIFY_PATH")
    }

    /**
     * Probe [gateway] itself rather than deriving it from an endpoint, for gateways the user typed in.
     */
    suspend fun probeGateway(gateway: String): UnifiedPushGatewayResolverResult {
        val url = tryOrNull { URL(gateway) } ?: return UnifiedPushGatewayResolverResult.ErrorInvalidUrl
        if (url.protocol != "http" && url.protocol != "https") return UnifiedPushGatewayResolverResult.ErrorInvalidUrl
        return probe(gateway)
    }

    private suspend fun probe(url: String): UnifiedPushGatewayResolverResult {
        Timber.i("Testing $url")
        return try {
            val response = matrix.rawService().getUrl(url, CacheStrategy.NoCache)
            val discovery = MatrixJsonParser.getMoshi().adapter(DiscoveryResponse::class.java).fromJson(response)
            if (discovery?.unifiedpush?.gateway == "matrix") {
                UnifiedPushGatewayResolverResult.Success(url)
            } else {
                Timber.w("$url answered, but not as a Matrix gateway")
                UnifiedPushGatewayResolverResult.NoMatrixGateway
            }
        } catch (throwable: Throwable) {
            val httpCode = (throwable as? Failure.OtherServerError)?.httpCode
                    ?: (throwable as? Failure.ServerError)?.httpCode
            when {
                httpCode in NO_GATEWAY_CODES -> {
                    Timber.i("Probing $url yielded $httpCode, there is no gateway there")
                    UnifiedPushGatewayResolverResult.NoMatrixGateway
                }
                else -> {
                    // A network error says nothing about the host, so the caller keeps what it had.
                    Timber.e(throwable, "Cannot probe $url")
                    UnifiedPushGatewayResolverResult.Error(url)
                }
            }
        }
    }

    companion object {
        const val NOTIFY_PATH = "/_matrix/push/v1/notify"

        private val NO_GATEWAY_CODES = listOf(
                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN,
                HttpURLConnection.HTTP_NOT_FOUND,
                HttpURLConnection.HTTP_BAD_METHOD,
                HttpURLConnection.HTTP_NOT_ACCEPTABLE,
        )
    }
}
