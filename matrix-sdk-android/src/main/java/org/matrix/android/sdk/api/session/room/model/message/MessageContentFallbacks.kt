/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.model.message

import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.toModel

/**
 * An m.room.message content as clients show it, with Element Web's fallbacks for what has no model: content
 * carrying a file reads as a file whatever its msgtype, and content with no msgtype at all shows its body.
 */
fun Content.toMessageContentWithFallbacks(): MessageContent? {
    val parsed = toModel<MessageContent>()
    if (parsed != null && parsed !is MessageDefaultContent) return parsed
    if (this["url"] is String || this["file"] is Map<*, *>) {
        val asFile = this + mapOf(MessageContent.MSG_TYPE_JSON_KEY to MessageType.MSGTYPE_FILE, "body" to (this["body"] as? String).orEmpty())
        asFile.toModel<MessageFileContent>()?.let { return it }
    }
    if (parsed != null || this[MessageContent.MSG_TYPE_JSON_KEY] != null) return parsed
    return (this["body"] as? String)?.let { MessageDefaultContent(msgType = "", body = it) }
}
