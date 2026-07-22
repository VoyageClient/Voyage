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
 * Class representing the EventType.STATE_ROOM_BANNER (MSC4221) state event content.
 * A removed banner is an empty content, i.e. all fields null.
 */
@JsonClass(generateAdapter = true)
data class RoomBannerContent(
        @Json(name = "url") val url: String? = null,
        @Json(name = "info") val info: BannerImageInfo? = null
)

@JsonClass(generateAdapter = true)
data class BannerImageInfo(
        @Json(name = "mimetype") val mimeType: String? = null
)
