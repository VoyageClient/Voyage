/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.share

import android.content.Context
import android.content.Intent
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.getRoomSummary
import org.matrix.android.sdk.api.session.room.model.message.toForwardPayload

/** Opens the room picker to forward [event], or its [editedContent] when it was edited. */
fun forwardEventIntent(context: Context, session: Session?, event: Event, editedContent: Content? = null): Intent {
    val payload = event.toForwardPayload(editedContent, includeSourceLink = !session.isDirectRoom(event.roomId))
    val payloadId = ForwardPayloadHolder.put(payload)
    return IncomingShareActivity.forwardIntent(context, event.getClearType(), payloadId)
}

fun Session?.isDirectRoom(roomId: String?): Boolean = roomId?.let { this?.getRoomSummary(it)?.isDirect } == true
