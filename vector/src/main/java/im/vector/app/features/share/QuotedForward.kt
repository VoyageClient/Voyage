/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.share

import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.message.ForwardedInfo
import org.matrix.android.sdk.api.session.room.model.message.MessageFormat
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.util.ContentUtils

/** Drops the MSC2723 metadata and block-quotes the text or media caption; anything else goes out as-is. */
fun quoteForwardContent(eventType: String, content: Map<String, Any?>): Map<String, Any?> {
    val stripped = content - ForwardedInfo.STABLE_KEY - ForwardedInfo.UNSTABLE_KEY
    if (eventType != EventType.MESSAGE) return stripped
    val rawBody = stripped["body"] as? String ?: return stripped
    val formatted = (stripped["formatted_body"] as? String)?.takeIf { stripped["format"] == MessageFormat.FORMAT_MATRIX_HTML }
    // A forwarded reply keeps its fallback; only <mx-reply> tells it apart from a leading quote.
    val body = if (formatted != null) ContentUtils.extractUsefulTextFromReply(rawBody, formatted) else rawBody
    val html = formatted?.let { ContentUtils.extractUsefulTextFromHtmlReply(it) }
    return when (stripped["msgtype"]) {
        MessageType.MSGTYPE_TEXT,
        MessageType.MSGTYPE_NOTICE -> stripped.withQuotedText(body, html)
        // Sent as-is, an emote would render as the forwarder's own action.
        MessageType.MSGTYPE_EMOTE -> stripped.withQuotedText(body, html, prefix = "* ") + ("msgtype" to MessageType.MSGTYPE_TEXT)
        MessageType.MSGTYPE_IMAGE,
        MessageType.MSGTYPE_VIDEO,
        MessageType.MSGTYPE_AUDIO,
        MessageType.MSGTYPE_FILE -> {
            // Without a distinct filename, body is the filename rather than an MSC2530 caption.
            val filename = (stripped["filename"] as? String)?.trim()
            val lastLine = body.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
            if (filename.isNullOrEmpty() || lastLine == null || lastLine == filename) stripped else stripped.withQuotedText(body, html)
        }
        else -> stripped
    }
}

private fun Map<String, Any?>.withQuotedText(body: String, formatted: String?, prefix: String = ""): Map<String, Any?> {
    val html = formatted ?: escapeHtml(body).replace("\n", "<br />")
    return this +
            ("body" to (prefix + body).lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }) +
            ("format" to MessageFormat.FORMAT_MATRIX_HTML) +
            ("formatted_body" to "<blockquote>${escapeHtml(prefix)}$html</blockquote>")
}

private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
