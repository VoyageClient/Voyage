/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.matrixcli

import retrofit2.http.GET

// A tiny unauthenticated client-server endpoint, used to prove core's Retrofit/OkHttp/Moshi stack
// makes real requests against a live homeserver from a desktop JVM.
internal interface VersionsApi {
    @GET("_matrix/client/versions")
    suspend fun versions(): VersionsResponse
}

internal data class VersionsResponse(val versions: List<String>)
