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
 * Class representing the EventType.STATE_ROOM_POLICY (MSC4284) state event content.
 * A disabled policy server is an empty content, i.e. all fields null.
 */
@JsonClass(generateAdapter = true)
data class RoomPolicyContent(
        @Json(name = "via") val via: String? = null,
        @Json(name = "public_keys") val publicKeys: Map<String, String>? = null,
        // Early unstable implementations send a single ed25519:policy_server key here instead.
        @Json(name = "public_key") val publicKey: String? = null
)
