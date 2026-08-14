/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync.sliding

import org.matrix.android.sdk.internal.network.NetworkConstants
import org.matrix.android.sdk.internal.network.TimeOutInterceptor
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Both MSCs describe `pos` and `timeout` as body fields, but Synapse parses them off the query
 * string (and matrix-js-sdk sends them there), so that is what actually works on the wire.
 */
internal interface SlidingSyncAPI {

    @POST(NetworkConstants.URI_API_PREFIX_PATH_UNSTABLE + "org.matrix.simplified_msc3575/sync")
    suspend fun simplifiedSlidingSync(
            @Query("pos") pos: String?,
            @Query("timeout") timeout: Long,
            @Body body: SlidingSyncRequest,
            @Header(TimeOutInterceptor.CONNECT_TIMEOUT) connectTimeOut: Long = TimeOutInterceptor.DEFAULT_LONG_TIMEOUT,
            @Header(TimeOutInterceptor.READ_TIMEOUT) readTimeOut: Long = TimeOutInterceptor.DEFAULT_LONG_TIMEOUT,
            @Header(TimeOutInterceptor.WRITE_TIMEOUT) writeTimeOut: Long = TimeOutInterceptor.DEFAULT_LONG_TIMEOUT
    ): SlidingSyncResponse

    @POST(NetworkConstants.URI_API_PREFIX_PATH_UNSTABLE + "org.matrix.msc4525/sync")
    suspend fun paginatedSync(
            @Query("pos") pos: String?,
            @Query("timeout") timeout: Long,
            @Body body: SlidingSyncRequest,
            @Header(TimeOutInterceptor.CONNECT_TIMEOUT) connectTimeOut: Long = TimeOutInterceptor.DEFAULT_LONG_TIMEOUT,
            @Header(TimeOutInterceptor.READ_TIMEOUT) readTimeOut: Long = TimeOutInterceptor.DEFAULT_LONG_TIMEOUT,
            @Header(TimeOutInterceptor.WRITE_TIMEOUT) writeTimeOut: Long = TimeOutInterceptor.DEFAULT_LONG_TIMEOUT
    ): SlidingSyncResponse
}
