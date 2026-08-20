/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.network

import im.vector.app.features.settings.useragent.UaSurface
import im.vector.app.features.settings.useragent.UserAgentSpoofBuilder
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Replaces the SDK's User-Agent with a user-chosen spoof on every SDK HTTP client. Wired via
 * MatrixConfiguration.networkInterceptors, which are re-ordered to run after the SDK's own
 * UserAgentInterceptor, so this wins. Reads settings per request; no restart needed to change it.
 */
class UserAgentOverrideInterceptor @Inject constructor(
        private val spoofBuilder: UserAgentSpoofBuilder,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val spoof = spoofBuilder.buildFor(UaSurface.API_MEDIA)
                ?: return chain.proceed(chain.request())
        val request = chain.request().newBuilder()
                .header("User-Agent", spoof)
                .build()
        return chain.proceed(request)
    }
}
