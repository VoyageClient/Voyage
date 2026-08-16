/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.model.message

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent

/**
 * Metadata about the message a forwarded message was copied from, as defined by MSC2723.
 */
@JsonClass(generateAdapter = true)
data class ForwardedInfo(
        @Json(name = "event_id") val eventId: String? = null,
        @Json(name = "room_id") val roomId: String? = null,
        @Json(name = "sender") val sender: String? = null,
        @Json(name = "origin_server_ts") val originServerTs: Long? = null
) {
    companion object {
        const val STABLE_KEY = "m.forwarded"
        const val UNSTABLE_KEY = "com.famedly.app.forwarded"
    }
}

@Suppress("UNCHECKED_CAST")
fun Content?.getForwardedInfo(): ForwardedInfo? {
    val raw = this?.get(ForwardedInfo.STABLE_KEY) ?: this?.get(ForwardedInfo.UNSTABLE_KEY)
    return (raw as? Map<String, Any>).toModel<ForwardedInfo>()
}

/**
 * MSC2723 metadata to add to a copy of this event forwarded elsewhere. Empty for an event with no
 * server-side identity to point back to.
 */
fun TimelineEvent.toForwardedInfoContent(): Map<String, Any> {
    // Forwarding a forward keeps pointing at the message the chain started from.
    val info = root.getClearContent().getForwardedInfo()?.toContentMap() ?: ownForwardedInfoContentMap() ?: return emptyMap()
    return mapOf(
            ForwardedInfo.STABLE_KEY to info,
            ForwardedInfo.UNSTABLE_KEY to info
    )
}

private fun ForwardedInfo.toContentMap(): Map<String, Any>? {
    return listOfNotNull(
            eventId?.let { "event_id" to it },
            roomId?.let { "room_id" to it },
            sender?.let { "sender" to it },
            originServerTs?.let { "origin_server_ts" to it }
    ).toMap().takeIf { it.isNotEmpty() }
}

private fun TimelineEvent.ownForwardedInfoContentMap(): Map<String, Any>? {
    val roomId = root.roomId
    val senderId = root.senderId
    if (roomId == null || senderId == null || !root.sendState.isSent()) return null
    return mapOf(
            "event_id" to eventId,
            "room_id" to roomId,
            "sender" to senderId,
            "origin_server_ts" to (root.originServerTs ?: 0L)
    )
}
