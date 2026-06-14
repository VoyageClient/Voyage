/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Class representing the EventType.STATE_ROOM_PINNED_EVENT state event content.
 */
@JsonClass(generateAdapter = true)
data class RoomPinnedEventsContent(
        @Json(name = "pinned") val pinned: List<String> = emptyList()
)
