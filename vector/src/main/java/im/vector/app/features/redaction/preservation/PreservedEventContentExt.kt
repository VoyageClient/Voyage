/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.redaction.preservation

import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.redaction.PreservedEventContent

fun PreservedEventContent.relationType(): String? =
        (content["m.relates_to"] as? Map<*, *>)?.get("rel_type") as? String

fun PreservedEventContent.relationKey(): String? =
        (content["m.relates_to"] as? Map<*, *>)?.get("key") as? String

/** A synthetic event carrying the preserved copy, for feeding back into ordinary aggregation UIs. */
fun PreservedEventContent.toEvent(): Event = Event(
        type = clearType?.takeIf { it.isNotEmpty() } ?: EventType.MESSAGE,
        eventId = eventId,
        roomId = roomId,
        senderId = senderId,
        originServerTs = originServerTs,
        content = content,
)
