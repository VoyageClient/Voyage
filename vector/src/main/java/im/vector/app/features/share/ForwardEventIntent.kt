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
import org.matrix.android.sdk.api.session.room.model.message.toForwardedInfoContent

/** Opens the room picker to forward [event], or its [editedContent] when it was edited. */
fun forwardEventIntent(context: Context, session: Session?, event: Event, editedContent: Content? = null): Intent {
    val baseContent = editedContent ?: event.getClearContent().orEmpty()
    // A DM's room id and sender are private to its members; a forwarded copy must not carry them.
    val isDmSource = event.roomId?.let { session?.getRoomSummary(it)?.isDirect } == true
    @Suppress("UNCHECKED_CAST")
    val forwardContent = (coerceWholeDoublesToLongs(baseContent - "m.relates_to") as Map<String, Any?>) +
            (if (isDmSource) emptyMap() else event.toForwardedInfoContent())
    val payloadId = ForwardPayloadHolder.put(forwardContent)
    return IncomingShareActivity.forwardIntent(context, event.getClearType(), payloadId)
}

// Whole-number numeric fields decode from JSON as Double; re-serializing emits e.g. "w":1080.0
// which Synapse rejects (M_BAD_JSON). Round-trip them back to Long.
private fun coerceWholeDoublesToLongs(value: Any?): Any? = when (value) {
    is Double -> if (value.isFinite() && value % 1.0 == 0.0 &&
            value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()) {
        value.toLong()
    } else value
    is Map<*, *> -> value.mapValues { coerceWholeDoublesToLongs(it.value) }
    is List<*> -> value.map { coerceWholeDoublesToLongs(it) }
    else -> value
}
