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
        val nextToken: String?,
        /** Targets of any redaction events seen in this page, for cross-referencing server redaction state. */
        val redactionTargets: List<String> = emptyList(),
        /** Events the server served as already redacted, for reconciling stale local copies. */
        val alreadyRedactedIds: List<String> = emptyList(),
)

/**
 * Lower bound for a mass-redaction history walk: [ts] of the user's earliest event, and a pagination
 * [anchorToken] at that event. When present, the walk pages FORWARDS from the anchor to the live edge,
 * which can never touch (or backfill) history from before the user was in the room.
 */
data class MassRedactionFloor(
        val ts: Long,
        val anchorToken: String?,
)

/**
 * Inclusive `origin_server_ts` bounds (ms) restricting a mass redaction to a slice of history, plus an
 * optional filter to skip state events (m.room.member etc) so only message events are redacted.
 */
data class MassRedactionRange(
        val fromTs: Long? = null,
        val toTs: Long? = null,
        val messagesOnly: Boolean = false,
) {

    // An event whose timestamp is unknown can't be proven to be inside a bounded range, and redaction
    // is irreversible — leave it alone.
    fun contains(ts: Long?): Boolean {
        if (fromTs == null && toTs == null) return true
        if (ts == null) return false
        return (fromTs == null || ts >= fromTs) && (toTs == null || ts <= toTs)
    }

    companion object {
        val ALL = MassRedactionRange()
    }
}
