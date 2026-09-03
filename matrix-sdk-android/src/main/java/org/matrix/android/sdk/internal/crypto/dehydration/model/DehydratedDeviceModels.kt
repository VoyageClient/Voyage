/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.crypto.dehydration.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.internal.crypto.model.rest.DeviceKeys

@JsonClass(generateAdapter = true)
internal data class DehydratedDeviceData(
        @Json(name = "algorithm")
        val algorithm: String?,

        @Json(name = "device_pickle")
        val devicePickle: String? = null,

        @Json(name = "nonce")
        val nonce: String? = null
)

@JsonClass(generateAdapter = true)
internal data class DehydratedDeviceResponse(
        @Json(name = "device_id")
        val deviceId: String?,

        @Json(name = "device_data")
        val deviceData: DehydratedDeviceData?
)

@JsonClass(generateAdapter = true)
internal data class PutDehydratedDeviceBody(
        @Json(name = "device_id")
        val deviceId: String,

        @Json(name = "device_data")
        val deviceData: DehydratedDeviceData,

        @Json(name = "initial_device_display_name")
        val initialDeviceDisplayName: String? = null,

        @Json(name = "device_keys")
        val deviceKeys: DeviceKeys,

        @Json(name = "one_time_keys")
        val oneTimeKeys: JsonDict? = null,

        @Json(name = "fallback_keys")
        val fallbackKeys: JsonDict? = null
)

@JsonClass(generateAdapter = true)
internal data class PutDehydratedDeviceResponse(
        @Json(name = "device_id")
        val deviceId: String?
)

@JsonClass(generateAdapter = true)
internal data class DehydratedDeviceEventsResponse(
        @Json(name = "events")
        val events: List<Event>?,

        /** Absent once the server has no more events to hand out. */
        @Json(name = "next_batch")
        val nextBatch: String?
)
