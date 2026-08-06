/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.admin

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.internal.network.NetworkConstants
import retrofit2.http.GET
import retrofit2.http.Path

@JsonClass(generateAdapter = true)
internal data class AdminStatusResult(
        @Json(name = "admin") val admin: Boolean? = null
)

internal interface AdminAPI {
    /**
     * Synapse-only. The handler asserts the caller is a server admin before doing anything, so the
     * response itself identifies both the server and the caller: 200 means admin, 403 means a
     * Synapse server that says no, and anything else means we cannot tell.
     */
    @GET(NetworkConstants.URI_SYNAPSE_ADMIN_PATH + "users/{userId}/admin")
    suspend fun getAdminStatus(@Path("userId") userId: String): AdminStatusResult
}
