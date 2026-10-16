/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.delayedevents

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.internal.network.NetworkConstants
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

// Synapse's shape: a delay query parameter on the ordinary send endpoints, and a list endpoint. The
// current MSC draft moves both to dedicated endpoints that no server implements yet.
internal interface DelayedEventsAPI {

    @PUT(NetworkConstants.URI_API_PREFIX_PATH_V3 + "rooms/{roomId}/send/{eventType}/{txnId}")
    suspend fun sendDelayedEvent(
            @Path("roomId") roomId: String,
            @Path("eventType") eventType: String,
            @Path("txnId") txnId: String,
            @Query(DELAY_PARAM) delayMs: Long,
            @Body content: Content,
    ): DelayIdResponse

    @PUT(NetworkConstants.URI_API_PREFIX_PATH_V3 + "rooms/{roomId}/state/{eventType}/{stateKey}")
    suspend fun sendDelayedStateEvent(
            @Path("roomId") roomId: String,
            @Path("eventType") eventType: String,
            @Path("stateKey") stateKey: String,
            @Query(DELAY_PARAM) delayMs: Long,
            @Body content: Content,
    ): DelayIdResponse

    @GET(NetworkConstants.URI_API_PREFIX_PATH_UNSTABLE + "org.matrix.msc4140/delayed_events")
    suspend fun getDelayedEvents(@Query("from") from: String? = null): DelayedEventsResponse

    // The body form is the one every Synapse release with MSC4140 accepts; the per-action paths came later.
    @POST(NetworkConstants.URI_API_PREFIX_PATH_UNSTABLE + "org.matrix.msc4140/delayed_events/{delayId}")
    suspend fun updateDelayedEvent(
            @Path("delayId") delayId: String,
            @Body body: UpdateDelayedEventBody,
    ): JsonDict

    companion object {
        const val DELAY_PARAM = "org.matrix.msc4140.delay"
    }
}

@JsonClass(generateAdapter = true)
internal data class DelayIdResponse(
        @Json(name = "delay_id") val delayId: String,
)

@JsonClass(generateAdapter = true)
internal data class UpdateDelayedEventBody(
        @Json(name = "action") val action: String,
) {
    companion object {
        val CANCEL = UpdateDelayedEventBody("cancel")
        val SEND = UpdateDelayedEventBody("send")
    }
}

@JsonClass(generateAdapter = true)
internal data class DelayedEventsResponse(
        @Json(name = "delayed_events") val delayedEvents: List<DelayedEventJson>? = null,
        @Json(name = "next_batch") val nextBatch: String? = null,
)

@JsonClass(generateAdapter = true)
internal data class DelayedEventJson(
        @Json(name = "delay_id") val delayId: String? = null,
        @Json(name = "room_id") val roomId: String? = null,
        @Json(name = "type") val type: String? = null,
        @Json(name = "state_key") val stateKey: String? = null,
        @Json(name = "content") val content: Content? = null,
        @Json(name = "delay_ms") val delayMs: Long? = null,
        @Json(name = "delayed_since_ts") val delayedSinceTs: Long? = null,
        // Field names from earlier MSC revisions, still all that older servers send.
        @Json(name = "delay") val delayLegacy: Long? = null,
        @Json(name = "running_since") val runningSinceLegacy: Long? = null,
)
