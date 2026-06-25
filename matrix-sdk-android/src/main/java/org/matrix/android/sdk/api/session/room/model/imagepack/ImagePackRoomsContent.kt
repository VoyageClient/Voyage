/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.model.imagepack

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * MSC2545 `m.image_pack.rooms` / `im.ponies.emote_rooms` account data: references to room image packs
 * the user has enabled globally. `rooms` maps roomId -> stateKey -> an opaque object (reserved for
 * future use; clients MUST preserve unknown keys).
 */
@JsonClass(generateAdapter = true)
data class ImagePackRoomsContent(
        @Json(name = "rooms") val rooms: Map<String, Map<String, Map<String, Any>>>? = null,
)
