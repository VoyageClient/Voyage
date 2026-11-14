/*
 * Copyright (c) 2021 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.federation

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import org.matrix.android.sdk.api.MatrixPatterns.getServerName
import org.matrix.android.sdk.api.auth.data.SessionParams
import org.matrix.android.sdk.api.federation.FederationVersion
import org.matrix.android.sdk.internal.network.NetworkConstants
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal interface GetFederationVersionTask : Task<Unit, FederationVersion>

internal class DefaultGetFederationVersionTask @Inject constructor(
        private val federationAPI: FederationAPI,
        private val federationUrlResolver: FederationUrlResolver,
        private val sessionParams: SessionParams,
) : GetFederationVersionTask {

    override suspend fun execute(params: Unit): FederationVersion {
        val federationUrl = federationUrlResolver.resolve(sessionParams.userId.getServerName())
        // Federation is often reverse-proxied onto the client API host too, which answers even when 8448 is unreachable.
        val result = firstAnswer(listOf(federationUrl, sessionParams.homeServerUrlBase.trimEnd('/')).distinct())
        return FederationVersion(
                name = result.server?.name,
                version = result.server?.version
        )
    }

    /** Fails with the first URL's error, as that one is the spec-resolved federation endpoint. */
    private suspend fun firstAnswer(baseUrls: List<String>): FederationGetVersionResult = coroutineScope {
        val attempts = baseUrls.map { baseUrl ->
            async {
                runCatching { executeRequest(null) { federationAPI.getVersion("$baseUrl/${NetworkConstants.URI_FEDERATION_PATH}version") } }
            }
        }
        val pending = attempts.toMutableList()
        while (pending.isNotEmpty()) {
            val (attempt, result) = select { pending.forEach { a -> a.onAwait { a to it } } }
            pending.remove(attempt)
            result.getOrNull()?.let { answer ->
                pending.forEach { it.cancel() }
                return@coroutineScope answer
            }
        }
        throw attempts.first().await().exceptionOrNull()!!
    }
}
