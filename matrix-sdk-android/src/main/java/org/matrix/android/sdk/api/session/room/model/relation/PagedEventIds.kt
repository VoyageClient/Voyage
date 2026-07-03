/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.model.relation

/** One page of event ids from a server history walk; [nextToken] null means the start of the room was reached. */
data class PagedEventIds(
        val eventIds: List<String>,
        val nextToken: String?
)
