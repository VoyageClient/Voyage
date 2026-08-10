/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.peeking

import org.matrix.android.sdk.api.session.events.model.Event

/**
 * Result of peeking a world-readable room via the v1 room initialSync endpoint.
 * [timelineEvents] are in chronological order (oldest first).
 * [backwardToken] seeds backward pagination through /messages.
 */
data class LiveRoomPeekSnapshot(
        val membership: String?,
        val stateEvents: List<Event>,
        val timelineEvents: List<Event>,
        val backwardToken: String?,
)

/**
 * A batch of live events for a peeked room from the v1 /events long-poll.
 * Pass [nextToken] back as `from` on the next poll. [startToken] is the stream position the
 * batch starts from (the passed `from`, or the current live position when `from` was null).
 */
data class LiveRoomPeekEvents(
        val events: List<Event>,
        val startToken: String?,
        val nextToken: String?,
)

/**
 * A backward page of history for a peeked room, from /messages.
 * [events] are in reverse-chronological order as returned by the server; [nextToken] is the
 * token for the next (older) page, null when the start of the room has been reached.
 * [stateEvents] carry lazy-loaded membership events relevant to the chunk.
 */
data class LiveRoomPeekPage(
        val events: List<Event>,
        val stateEvents: List<Event>,
        val nextToken: String?,
)
