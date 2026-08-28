/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.extensions

import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent

/**
 * Wrap an event that only lives in the event cache — it has no timeline row, so
 * [org.matrix.android.sdk.api.session.room.timeline.TimelineService.getTimelineEvent] returns null for it.
 * Annotations are keyed by event id alone, so edits and reactions resolve here exactly as they do for an
 * event the timeline holds; without them the event renders in its original, pre-edit form.
 */
fun Session.toCachedTimelineEvent(roomId: String, event: Event): TimelineEvent? {
    val eventId = event.eventId ?: return null
    val senderId = event.senderId ?: ""
    val member = roomService().getRoomMember(senderId, roomId)
    return TimelineEvent(
            root = event,
            localId = eventId.hashCode().toLong(),
            eventId = eventId,
            displayIndex = 0,
            senderInfo = SenderInfo(senderId, member?.displayName, isUniqueDisplayName = true, member?.avatarUrl),
            annotations = getRoom(roomId)?.relationService()?.getEventAnnotationsSummary(eventId),
    )
}
