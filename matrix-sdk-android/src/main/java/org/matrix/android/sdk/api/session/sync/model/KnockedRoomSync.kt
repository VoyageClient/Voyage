/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.sync.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// KnockedRoomSync represents a room the user has knocked on (requested to join) during server sync v2.
@JsonClass(generateAdapter = true)
data class KnockedRoomSync(

        /**
         * The stripped state of a room that the user has knocked on. Same shape as invite_state.
         */
        @Json(name = "knock_state") val knockState: RoomInviteState? = null
)
