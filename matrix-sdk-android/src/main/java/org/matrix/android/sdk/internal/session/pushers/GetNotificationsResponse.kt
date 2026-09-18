/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.pushers

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.session.events.model.Event

/** Response of `GET /notifications`: what the server's evaluation of our push rules actually notified. */
@JsonClass(generateAdapter = true)
internal data class GetNotificationsResponse(
        /** Pass back as `from` to page further into the past; absent once the history ends. */
        @Json(name = "next_token") val nextToken: String? = null,
        @Json(name = "notifications") val notifications: List<NotificationResult> = emptyList(),
)

@JsonClass(generateAdapter = true)
internal data class NotificationResult(
        @Json(name = "room_id") val roomId: String? = null,
        @Json(name = "event") val event: Event? = null,
        @Json(name = "ts") val ts: Long? = null,
        @Json(name = "read") val read: Boolean? = null,
        /** The actions the matched rule carried, e.g. a `set_tweak: highlight`. */
        @Json(name = "actions") val actions: List<Any>? = null,
)
