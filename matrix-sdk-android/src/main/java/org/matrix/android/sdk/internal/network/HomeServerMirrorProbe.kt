/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.network

import dagger.Lazy
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.internal.di.UnauthenticatedWithCertificate
import org.matrix.android.sdk.internal.session.SessionScope
import timber.log.Timber
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Checks whether a mirror the user ranked above the one in use is back up, so that a fallback does not
 * become permanent.
 */
@SessionScope
internal class HomeServerMirrorProbe @Inject constructor(
        @UnauthenticatedWithCertificate private val okHttpClient: Lazy<OkHttpClient>,
        private val tracker: HomeServerFallbackTracker,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
) {

    /**
     * Switch back to the highest ranked mirror that answers. Returns the mirror in use afterwards.
     */
    suspend fun probePreferred(): String = withContext(coroutineDispatchers.io) {
        val configured = tracker.configured()
        val active = tracker.active()
        val preferred = configured.indexOf(active).let { if (it < 0) configured.size else it }
        if (preferred == 0) return@withContext active

        val client = okHttpClient.get().newBuilder()
                .connectTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        configured.take(preferred).firstOrNull { client.check(it) }
                ?.also {
                    Timber.i("Homeserver mirror $it is back up, switching to it")
                    tracker.onReached(it)
                }
                ?: active
    }

    /**
     * Probing off the request path is free, so a mirror found still down is marked as such here rather
     * than leaving the next request to rediscover it at the cost of a connect timeout.
     */
    private fun OkHttpClient.check(base: String): Boolean {
        val request = Request.Builder()
                .url(base + "_matrix/client/versions")
                .header(HomeServerFallbackInterceptor.PROBE_HEADER, "1")
                .build()
        return try {
            val reachable = newCall(request).execute().use { it.isSuccessful }
            if (!reachable) tracker.markDown(base)
            reachable
        } catch (failure: UnknownHostException) {
            // The device being offline, not this mirror being down.
            false
        } catch (failure: IOException) {
            tracker.markDown(base)
            false
        }
    }

    companion object {
        private const val PROBE_TIMEOUT_SECONDS = 10L
    }
}
