/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.homeserver

import org.matrix.android.sdk.api.auth.data.AuthMetadata
import org.matrix.android.sdk.internal.network.NetworkConstants
import retrofit2.http.GET

internal interface AuthMetadataAPI {
    /**
     * Request the homeserver OAuth 2.0 auth metadata.
     */
    @GET(NetworkConstants.URI_API_PREFIX_PATH_V1 + "auth_metadata")
    suspend fun getAuthMetadata(): AuthMetadata
}
