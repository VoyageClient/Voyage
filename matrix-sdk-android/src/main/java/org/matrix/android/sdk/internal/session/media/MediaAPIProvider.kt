/*
 * Copyright (C) 2024 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.media

import org.matrix.android.sdk.api.util.JsonDict
import javax.inject.Inject

internal class MediaAPIProvider @Inject constructor(
        private val isAuthenticatedMediaSupported: IsAuthenticatedMediaSupported,
        private val authenticatedMediaAPI: AuthenticatedMediaAPI,
        private val unauthenticatedMediaAPI: UnauthenticatedMediaAPI,
) {

    // The two Retrofit services can't share a MediaAPI supertype (Retrofit 2.6.x rejects service
    // interfaces that extend others), so bridge the selected one to the common MediaAPI here.
    fun getMediaAPI(): MediaAPI {
        return if (isAuthenticatedMediaSupported()) {
            object : MediaAPI {
                override suspend fun getMediaConfig(): GetMediaConfigResult = authenticatedMediaAPI.getMediaConfig()
                override suspend fun getPreviewUrlData(url: String, ts: Long?): JsonDict = authenticatedMediaAPI.getPreviewUrlData(url, ts)
            }
        } else {
            object : MediaAPI {
                override suspend fun getMediaConfig(): GetMediaConfigResult = unauthenticatedMediaAPI.getMediaConfig()
                override suspend fun getPreviewUrlData(url: String, ts: Long?): JsonDict = unauthenticatedMediaAPI.getPreviewUrlData(url, ts)
            }
        }
    }
}
