/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.factory

import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.helper.TimelineEventsGroup
import im.vector.app.features.home.room.detail.timeline.item.ReactionsSummaryEvents
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent

data class TimelineItemFactoryParams(
        val event: TimelineEvent,
        val lastEdit: Event? = null,
        val prevEvent: TimelineEvent? = null,
        val prevDisplayableEvent: TimelineEvent? = null,
        val nextEvent: TimelineEvent? = null,
        val nextDisplayableEvent: TimelineEvent? = null,
        val partialState: TimelineEventController.PartialState = TimelineEventController.PartialState(),
        val lastSentEventIdWithoutReadReceipts: String? = null,
        val callback: TimelineEventController.Callback? = null,
        val reactionsSummaryEvents: ReactionsSummaryEvents? = null,
        val eventsGroup: TimelineEventsGroup? = null,
        val forcedVisibleEventIds: Set<String> = emptySet(),
        // Set when [event] is a redacted event whose original content has been substituted back in,
        // so items can mark it as revealed even though it no longer looks redacted.
        val isRevealedRedaction: Boolean = false,
        // For hosts outside the timeline which date each row themselves.
        val hideTimestamp: Boolean = false
) {

    val highlightedEventId: String?
        get() = partialState.highlightedEventId

    val highlightNonce: Long
        get() = partialState.highlightNonce

    val rootThreadEventId: String?
        get() = partialState.rootThreadEventId

    val isHighlighted = highlightedEventId == event.eventId

    fun isFromThreadTimeline(): Boolean = rootThreadEventId != null
}
