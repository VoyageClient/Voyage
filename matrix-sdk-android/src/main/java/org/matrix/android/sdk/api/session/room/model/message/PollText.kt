/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.model.message

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PollText(
        @Json(name = "body") val body: String,
        @Json(name = "mimetype") val mimetype: String = "text/plain"
)

internal fun List<PollText>.plainText(): String? = firstOrNull { it.mimetype == "text/plain" }?.body
