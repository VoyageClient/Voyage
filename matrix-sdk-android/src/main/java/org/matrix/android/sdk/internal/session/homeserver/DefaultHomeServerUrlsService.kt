/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.homeserver

import org.matrix.android.sdk.api.session.homeserver.HomeServerUrlsService
import org.matrix.android.sdk.internal.auth.SessionParamsStore
import org.matrix.android.sdk.internal.di.SessionId
import org.matrix.android.sdk.internal.network.HomeServerFallbackTracker
import org.matrix.android.sdk.internal.network.HomeServerMirrorProbe
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.util.ensureTrailingSlash
import javax.inject.Inject

@SessionScope
internal class DefaultHomeServerUrlsService @Inject constructor(
        @SessionId private val sessionId: String,
        private val sessionParamsStore: SessionParamsStore,
        private val fallbackTracker: HomeServerFallbackTracker,
        private val mirrorProbe: HomeServerMirrorProbe,
) : HomeServerUrlsService {

    override fun getHomeServerUrls() = fallbackTracker.configured()

    override fun getActiveHomeServerUrl() = fallbackTracker.active()

    override suspend fun refreshActiveHomeServerUrl() = mirrorProbe.probePreferred()

    override suspend fun setHomeServerUrls(urls: List<String>) {
        val normalized = urls.map { it.ensureTrailingSlash() }.distinct()
        require(normalized.isNotEmpty()) { "At least one homeserver URL is required" }

        sessionParamsStore.updateHomeServerConnectionConfig(sessionId) {
            it.copy(
                    homeServerUriBase = normalized.first(),
                    fallbackHomeServerUriBases = normalized.drop(1),
            )
        }
        fallbackTracker.update(normalized)
    }
}
