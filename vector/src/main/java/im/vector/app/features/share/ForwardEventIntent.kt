/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.share

import android.content.Context
import android.content.Intent
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.getRoomSummary
import org.matrix.android.sdk.api.session.room.getStateEvent
import org.matrix.android.sdk.api.session.room.model.RoomAvatarContent
import org.matrix.android.sdk.api.session.room.model.message.ForwardSourceRoom
import org.matrix.android.sdk.api.session.room.model.message.toForwardPayload
import org.matrix.android.sdk.api.session.room.model.message.forwardOrigin as eventForwardOrigin

/** Opens the room picker to forward [event], or its [editedContent] when it was edited. */
fun forwardEventIntent(context: Context, session: Session?, event: Event, editedContent: Content? = null): Intent {
    val payloadId = ForwardPayloadHolder.put(session.forwardPayload(event, editedContent))
    return IncomingShareActivity.forwardIntent(context, event.getClearType(), payloadId)
}

/** [toForwardPayload] for [event], linking back to its room unless it is a DM. */
fun Session?.forwardPayload(event: Event, editedContent: Content? = null): Content {
    val sourceRoom = forwardSourceRoom(event.roomId)
    return event.toForwardPayload(editedContent, includeSourceLink = sourceRoom != null, sourceRoom = sourceRoom)
}

/** The `m.forwarded` origin fields for [event], linking back to its room unless it is a DM. */
fun Session?.forwardOrigin(event: Event): Map<String, Any> {
    val sourceRoom = forwardSourceRoom(event.roomId)
    return event.eventForwardOrigin(includeSourceLink = sourceRoom != null, sourceRoom = sourceRoom)
}

// Read from room state: the summary's display name and avatar carry the user's personal overrides.
private fun Session?.forwardSourceRoom(roomId: String?): ForwardSourceRoom? {
    val summary = roomId?.let { this?.getRoomSummary(it) }
    if (summary?.isDirect == true) return null
    val avatarUrl = roomId?.let { this?.getRoom(it) }
            ?.getStateEvent(EventType.STATE_ROOM_AVATAR, QueryStringValue.IsEmpty)
            ?.content.toModel<RoomAvatarContent>()
            ?.avatarUrl
    return ForwardSourceRoom(name = summary?.name?.ifBlank { null } ?: summary?.canonicalAlias, avatarUrl = avatarUrl)
}
