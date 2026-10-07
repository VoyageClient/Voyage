/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.model.message

import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.permalinks.PermalinkService
import org.matrix.android.sdk.api.util.MatrixJsonParser

/**
 * Builds the content of a forwarded event (MSC4553) from a payload made by [toForwardPayload].
 *
 * The forward carries `m.forwarded` under both the stable and MSC4553's unstable key. A [silent] forward
 * keeps the message but says nothing about where it came from.
 */
object ForwardContentBuilder {

    /**
     * @param senderDisplayName the original sender's display name, which a forwarded emote's fallback names
     * @param viaServers servers to offer in the fallback's link to the original message
     */
    fun build(
            eventType: String,
            payload: Content,
            silent: Boolean = false,
            senderDisplayName: String? = null,
            viaServers: List<String> = emptyList(),
    ): Content {
        val raw = payload.rawForwarded().orEmpty()
        val origin = if (silent) emptyMap() else raw.filterKeys { it in ForwardedInfo.ORIGIN_FIELDS }
        @Suppress("UNCHECKED_CAST")
        val original = (raw["content"] as? Content)?.takeIf { eventType == EventType.MESSAGE }
        if (original == null) {
            val metadata = if (origin.isEmpty()) {
                emptyMap()
            } else {
                mapOf(ForwardedInfo.STABLE_KEY to origin, ForwardedInfo.UNSTABLE_KEY to origin)
            }
            return coerceWholeDoublesToLongs(payload - ForwardedInfo.KEYS + metadata + (MENTIONS to emptyMap<String, Any>()))
        }
        val forwarded = if (silent) mapOf("content" to original) else raw
        val links = FallbackLinks(
                sender = origin["sender"] as? String,
                senderDisplayName = senderDisplayName,
                eventPermalink = eventPermalink(origin["room_id"] as? String, origin["event_id"] as? String, viaServers),
        )
        val withQuote = original.toTopLevel(links, quote = true)
        val candidates = listOf(
                withQuote + forwardedKeys(forwarded, includeStable = true),
                withQuote + forwardedKeys(forwarded, includeStable = false),
                original.toTopLevel(links, quote = false) + forwardedKeys(forwarded, includeStable = false),
        )
        // The message is carried more than once, so shed the redundant copies until it fits.
        val fitting = candidates.firstOrNull { it.jsonSize() <= MAX_CONTENT_BYTES } ?: candidates.last()
        return coerceWholeDoublesToLongs(fitting)
    }

    private fun forwardedKeys(forwarded: Map<String, Any?>, includeStable: Boolean): Map<String, Any?> =
            listOfNotNull(
                    if (includeStable) ForwardedInfo.STABLE_KEY to forwarded else null,
                    ForwardedInfo.UNSTABLE_KEY to forwarded,
                    MENTIONS to emptyMap<String, Any>(),
            ).toMap()

    private class FallbackLinks(val sender: String?, val senderDisplayName: String?, val eventPermalink: String?)

    private fun Content.toTopLevel(links: FallbackLinks, quote: Boolean): Map<String, Any?> {
        val base = this - ForwardedInfo.KEYS - "m.relates_to" - MENTIONS - "m.new_content"
        val msgType = this["msgtype"] as? String
        val body = this["body"] as? String ?: ""
        val html = (this["formatted_body"] as? String)?.takeIf { this["format"] == MessageFormat.FORMAT_MATRIX_HTML }
        val quoted: Pair<String, String>?
        var top: Map<String, Any?> = base
        when (msgType) {
            MessageType.MSGTYPE_TEXT, MessageType.MSGTYPE_NOTICE -> {
                quoted = (body to (html ?: escapeHtml(body).withBreaks())).takeIf { body.isNotEmpty() }
            }
            MessageType.MSGTYPE_EMOTE -> {
                // Rendered as an emote, it would read as the forwarder's own action.
                top = top + ("msgtype" to MessageType.MSGTYPE_TEXT)
                val actor = links.sender?.let { links.senderDisplayName ?: it } ?: GENERIC_ACTOR
                val actorHtml = links.sender?.let { "<a href=\"${escapeHtml(userPermalink(it))}\">${escapeHtml(actor)}</a>" } ?: GENERIC_ACTOR
                // Styled the way most clients render an emote, so it doesn't read as the actor's own words.
                quoted = ("$actor $body" to "<em><strong>$actorHtml</strong> ${html ?: escapeHtml(body).withBreaks()}</em>").takeIf { body.isNotEmpty() }
            }
            MessageType.MSGTYPE_IMAGE, MessageType.MSGTYPE_VIDEO, MessageType.MSGTYPE_AUDIO, MessageType.MSGTYPE_FILE -> {
                val filename = this["filename"] as? String
                top = top + ("filename" to (filename ?: body))
                val hasCaption = filename != null && filename != body
                quoted = (body to (html ?: escapeHtml(body).withBreaks())).takeIf { hasCaption && body.isNotEmpty() }
            }
            else -> return base
        }
        val sender = links.sender
        val header = (if (sender != null) "Forwarded from $sender" else "Forwarded message") +
                (links.eventPermalink?.let { " - view original message: $it" } ?: "")
        val headerHtml = "<strong>" +
                (if (sender != null) "Forwarded from <a href=\"${escapeHtml(userPermalink(sender))}\">${escapeHtml(sender)}</a>" else "Forwarded message") +
                (links.eventPermalink?.let { " - <a href=\"${escapeHtml(it)}\">view original message</a>" } ?: "") +
                "</strong>"
        val shown = quoted.takeIf { quote }
        return top +
                ("body" to (shown?.let { "$header\n${it.first}" } ?: header)) +
                ("format" to MessageFormat.FORMAT_MATRIX_HTML) +
                ("formatted_body" to (shown?.let { "$headerHtml<blockquote>${it.second}</blockquote>" } ?: headerHtml))
    }

    private fun eventPermalink(roomId: String?, eventId: String?, via: List<String>): String? {
        if (roomId == null || eventId == null) return null
        val query = via.distinct().joinToString("&") { "via=${encodeComponent(it)}" }.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""
        return "${PermalinkService.MATRIX_TO_URL_BASE}${encodeComponent(roomId)}/${encodeComponent(eventId)}$query"
    }

    private fun userPermalink(userId: String) = PermalinkService.MATRIX_TO_URL_BASE + encodeComponent(userId)

    // encodeURIComponent: URLEncoder would also escape '!' and turn spaces into '+'.
    internal fun encodeComponent(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val c = byte.toInt() and 0xFF
            if (c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() in "-_.!~*'()")) {
                append(c.toChar())
            } else {
                append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
            }
        }
    }

    private fun escapeHtml(text: String): String = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun String.withBreaks() = replace("\n", "<br>")

    private fun Map<String, Any?>.jsonSize(): Int =
            MatrixJsonParser.getMoshi().adapter(Map::class.java).toJson(this).toByteArray(Charsets.UTF_8).size

    // Moshi reads every JSON number as a Double; re-sent as is, "w": 1080 becomes 1080.0, which Synapse rejects.
    @Suppress("UNCHECKED_CAST")
    private fun coerceWholeDoublesToLongs(content: Map<String, Any?>): Content = coerce(content) as Content

    private fun coerce(value: Any?): Any? = when (value) {
        is Double -> if (value.isFinite() && value % 1.0 == 0.0 && value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()) {
            value.toLong()
        } else {
            value
        }
        is Map<*, *> -> value.mapValues { coerce(it.value) }
        is List<*> -> value.map { coerce(it) }
        else -> value
    }

    private const val MENTIONS = "m.mentions"
    private const val GENERIC_ACTOR = "Sender"
    private const val HEX = "0123456789ABCDEF"

    // Leaves room under the 64 KiB event limit for Megolm's base64 growth and the event envelope.
    private const val MAX_CONTENT_BYTES = 45_000
}
