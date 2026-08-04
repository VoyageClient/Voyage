/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Retries a request against the homeserver's configured mirrors when the current one cannot be reached, or
 * answers with a gateway error (a proxy that is up but whose backend is not).
 */
internal class HomeServerFallbackInterceptor @Inject constructor(
        private val tracker: HomeServerFallbackTracker
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(PROBE_HEADER) != null) {
            // A mirror probe targets one specific mirror; rewriting or retrying it would defeat the point.
            return chain.proceed(request.newBuilder().removeHeader(PROBE_HEADER).build())
        }

        if (!tracker.hasMirrors()) return chain.proceed(request)
        val suffix = request.url().toString().removePrefixOrNull(tracker.requestBase)
                ?: return chain.proceed(request)
        val candidates = tracker.candidates()

        // Endpoints such as /sync ask for a 60s connect timeout, which would make every request in flight
        // when a mirror dies hang for a minute before failing over. Reaching a live mirror is fast or never.
        val attemptChain = chain.withConnectTimeout(FAILOVER_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        var lastFailure: IOException? = null
        var lastResponse: Response? = null
        for (candidate in candidates) {
            val url = HttpUrl.parse(candidate + suffix) ?: continue
            var offline = false
            try {
                val response = attemptChain.proceed(request.newBuilder().url(url).build())
                if (response.code() !in FAILOVER_CODES) {
                    tracker.onReached(candidate)
                    lastResponse?.close()
                    return response
                }
                lastResponse?.close()
                lastResponse = response
            } catch (failure: IOException) {
                lastFailure = failure
                // DNS/no-route failures are the device being offline, not this mirror being down — and they
                // come back in milliseconds, so trying the rest costs nothing.
                offline = failure is UnknownHostException
            }
            if (!offline) {
                // Spare every other request in flight the same wait until the mirror has had time to recover.
                tracker.markDown(candidate)
                Timber.w("Homeserver mirror $candidate did not answer")
            }
        }

        return lastResponse ?: throw (lastFailure ?: IOException("No usable homeserver mirror"))
    }

    private fun String.removePrefixOrNull(prefix: String) = takeIf { it.startsWith(prefix) }?.substring(prefix.length)

    companion object {
        const val PROBE_HEADER = "X-Matrix-Mirror-Probe"

        private const val FAILOVER_CONNECT_TIMEOUT_SECONDS = 10
        private val FAILOVER_CODES = setOf(502, 503, 504)
    }
}
