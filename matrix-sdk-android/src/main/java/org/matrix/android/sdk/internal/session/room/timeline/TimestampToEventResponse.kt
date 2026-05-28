/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Response shape for `GET /_matrix/client/v1/rooms/{roomId}/timestamp_to_event` (MSC3030).
 */
@JsonClass(generateAdapter = true)
internal data class TimestampToEventResponse(
        @Json(name = "event_id") val eventId: String,
        @Json(name = "origin_server_ts") val originServerTs: Long,
)
