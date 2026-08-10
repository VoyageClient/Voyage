/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.peeking

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.internal.session.room.timeline.PaginationResponse

@JsonClass(generateAdapter = true)
internal data class RoomInitialSyncResponse(
        @Json(name = "room_id") val roomId: String? = null,
        @Json(name = "membership") val membership: String? = null,
        @Json(name = "messages") val messages: PaginationResponse? = null,
        @Json(name = "state") val state: List<Event>? = null,
)

@JsonClass(generateAdapter = true)
internal data class PeekEventsResponse(
        @Json(name = "start") val start: String? = null,
        @Json(name = "end") val end: String? = null,
        @Json(name = "chunk") val chunk: List<Event>? = null,
)
