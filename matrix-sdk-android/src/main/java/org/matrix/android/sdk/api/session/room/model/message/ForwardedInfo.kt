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
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.util.ContentUtils

/** MSC4553 metadata; missing or invalid [content] falls back to the enclosing event's content. */
@JsonClass(generateAdapter = true)
data class ForwardedInfo(
        @Json(name = "event_id") val eventId: String? = null,
        @Json(name = "room_id") val roomId: String? = null,
        @Json(name = "sender") val sender: String? = null,
        @Json(name = "origin_server_ts") val originServerTs: Long? = null,
        @Json(name = "content") val content: Content? = null,
) {
    companion object {
        const val STABLE_KEY = "m.forwarded"
        const val UNSTABLE_KEY = "org.matrix.msc4553.forwarded"
        const val LEGACY_KEY = "com.famedly.app.forwarded"

        val KEYS = listOf(STABLE_KEY, UNSTABLE_KEY, LEGACY_KEY)

        internal val ORIGIN_FIELDS = listOf("event_id", "room_id", "sender", "origin_server_ts")
    }
}

private fun Map<*, *>.isValidForwardedContent(eventType: String): Boolean = when (eventType) {
    EventType.MESSAGE -> this["msgtype"] is String && this["body"] is String
    EventType.STICKER -> this["body"] is String && this["info"] is Map<*, *> &&
            (this["url"] is String || (this["file"] as? Map<*, *>)?.get("url") is String)
    else -> false
}

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>?.rawForwarded(): Map<String, Any?>? =
        ForwardedInfo.KEYS.firstNotNullOfOrNull { this?.get(it) as? Map<String, Any?> }

fun Content?.isForward(): Boolean = rawForwarded() != null

@Suppress("UNCHECKED_CAST")
fun Content?.getForwardedInfo(): ForwardedInfo? = (rawForwarded() as? Map<String, Any>).toModel<ForwardedInfo>()

/**
 * What a supporting client renders for a forward with valid MSC4553 `content`: the original message,
 * carrying the forward's own relation and mentions rather than the ones it had where it was sent.
 */
@Suppress("UNCHECKED_CAST")
fun Content?.forwardedDisplayContent(eventType: String = EventType.MESSAGE): Content? {
    val inner = (rawForwarded()?.get("content") as? Content)?.takeIf { it.isValidForwardedContent(eventType) } ?: return null
    val outer = this ?: return null
    return inner - OWN_RELATION_KEYS - ForwardedInfo.KEYS +
            OWN_RELATION_KEYS.mapNotNull { key -> outer[key]?.let { key to it } }
}

private val OWN_RELATION_KEYS = listOf("m.relates_to", "m.mentions", "m.new_content")

/**
 * What the room picker needs to forward this event, with [editedContent] standing in for an edited message.
 * [includeSourceLink] controls room and event IDs for a new forward; existing forwards keep their origin.
 */
fun Event.toForwardPayload(editedContent: Content? = null, includeSourceLink: Boolean = true): Content {
    val eventType = getClearType()
    require(eventType == EventType.MESSAGE || eventType == EventType.STICKER)
    val clear = getClearContent().orEmpty()
    val existing = clear.rawForwarded()
    val origin = forwardOrigin(includeSourceLink)
    if (existing != null) {
        // A forward of a forward points at the original message, not at the copy in between.
        val forwarded = if ((existing["content"] as? Map<*, *>)?.isValidForwardedContent(eventType) == true) {
            existing
        } else {
            existing + ("content" to (editedContent ?: clear).toForwardedContent())
        }
        return mapOf(ForwardedInfo.STABLE_KEY to forwarded)
    }
    return messageForwardPayload((editedContent ?: clear).toForwardedContent(), origin)
}

/** A forward payload for [content] that is already the message to forward, such as one item of a gallery. */
fun messageForwardPayload(content: Content, origin: Map<String, Any>): Content =
        mapOf(ForwardedInfo.STABLE_KEY to (origin + ("content" to content)))

/** Event, room and sender ids of this event, for an `m.forwarded` object; a local echo has no event id yet. */
fun Event.forwardOrigin(includeSourceLink: Boolean = true): Map<String, Any> = listOfNotNull(
        eventId?.takeIf { includeSourceLink && !LocalEcho.isLocalEchoId(it) }?.let { "event_id" to it },
        roomId?.takeIf { includeSourceLink }?.let { "room_id" to it },
        senderId?.let { "sender" to it },
        originServerTs?.let { "origin_server_ts" to it },
).toMap()

private fun Content.toForwardedContent(): Content {
    val stripped = this - ForwardedInfo.KEYS
    val isReply = ((stripped["m.relates_to"] as? Map<*, *>)?.get("m.in_reply_to") as? Map<*, *>)?.get("event_id") != null
    if (!isReply) return stripped
    val body = stripped["body"] as? String
    val formatted = (stripped["formatted_body"] as? String)?.takeIf { stripped["format"] == MessageFormat.FORMAT_MATRIX_HTML }
    return stripped +
            listOfNotNull(
                    body?.let { "body" to ContentUtils.extractUsefulTextFromReply(it, formatted) },
                    formatted?.let { "formatted_body" to ContentUtils.extractUsefulTextFromHtmlReply(it) },
            )
}
