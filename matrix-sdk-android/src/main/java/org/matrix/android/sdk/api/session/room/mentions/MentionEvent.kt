/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.mentions

import org.matrix.android.sdk.api.session.events.model.Event

/** Why an event is in the mentions list. */
enum class MentionKind {
    /** `m.mentions.user_ids` holds our user id. */
    USER,

    /** `m.mentions.room` is set by someone allowed to notify the room. */
    ROOM,

    /** One of the account's own keyword push rules matched the body. */
    KEYWORD,
}

/** One hit of [org.matrix.android.sdk.api.session.room.RoomService.getMentions]. */
data class MentionEvent(
        val roomId: String,
        val eventId: String,
        val originServerTs: Long,
        val kind: MentionKind,
        /**
         * The clear event the match was decided on. A hit found only in the search index has no row in
         * the synced timeline, so this is all there is to render it with.
         */
        val event: Event,
)

/** A page of [MentionEvent]s, with the token that fetches the page before it. */
data class MentionsResult(
        val events: List<MentionEvent>,
        /** Pass as [MentionsQueryParams.from] for the next page; null once the history ends. */
        val nextToken: String? = null,
)

data class MentionsQueryParams(
        val includeRoomMentions: Boolean = true,
        val includeKeywords: Boolean = true,
        /** Most recent hits only; the scan is over every room's stored events. */
        val limit: Int = 200,
        /**
         * A previous [MentionsResult.nextToken], to read further back. Only the server can page, so a
         * continuation skips the local sources, which already gave everything they hold on the first page.
         */
        val from: String? = null,
)
