/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.useragent.data

import com.squareup.moshi.JsonClass

/**
 * One selectable value for a fetched field. Providers return these newest-first (recency order); the
 * dialog re-sorts by [share] for the "most used" view.
 *
 * @param value what goes into the User-Agent field
 * @param label human display, e.g. the version with its usage percentage
 * @param share current worldwide usage %, or null when no usage data exists (e.g. curl)
 */
@JsonClass(generateAdapter = true)
data class UaOption(
        val value: String,
        val label: String,
        val share: Double? = null,
)
