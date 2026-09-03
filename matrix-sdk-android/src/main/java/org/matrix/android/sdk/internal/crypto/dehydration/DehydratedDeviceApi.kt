/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.dehydration

import org.matrix.android.sdk.internal.crypto.dehydration.model.DehydratedDeviceEventsResponse
import org.matrix.android.sdk.internal.crypto.dehydration.model.DehydratedDeviceResponse
import org.matrix.android.sdk.internal.crypto.dehydration.model.PutDehydratedDeviceBody
import org.matrix.android.sdk.internal.crypto.dehydration.model.PutDehydratedDeviceResponse
import org.matrix.android.sdk.internal.network.NetworkConstants
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * MSC3814: dehydrated devices with SSSS.
 */
internal interface DehydratedDeviceApi {

    @GET(NetworkConstants.URI_API_PREFIX_PATH_UNSTABLE + "org.matrix.msc3814.v1/dehydrated_device")
    suspend fun getDehydratedDevice(): DehydratedDeviceResponse

    @PUT(NetworkConstants.URI_API_PREFIX_PATH_UNSTABLE + "org.matrix.msc3814.v1/dehydrated_device")
    suspend fun putDehydratedDevice(@Body body: PutDehydratedDeviceBody): PutDehydratedDeviceResponse

    @DELETE(NetworkConstants.URI_API_PREFIX_PATH_UNSTABLE + "org.matrix.msc3814.v1/dehydrated_device")
    suspend fun deleteDehydratedDevice(): PutDehydratedDeviceResponse

    @GET(NetworkConstants.URI_API_PREFIX_PATH_UNSTABLE + "org.matrix.msc3814.v1/dehydrated_device/{deviceId}/events")
    suspend fun getEvents(
            @Path("deviceId") deviceId: String,
            @Query("from") from: String?,
            @Query("limit") limit: Int?
    ): DehydratedDeviceEventsResponse
}
