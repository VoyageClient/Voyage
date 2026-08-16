/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.search

import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.message.MessageType

/** The `key:value` filters a search term understands, shared by the parser and the UI that suggests them. */
object SearchFilters {

    const val FROM = "from"
    const val MENTIONS = "mentions"
    const val HAS = "has"
    const val AFTER = "after"
    const val BEFORE = "before"

    /** The one `has:` value that isn't a msgtype: it asks for a message carrying a URL. */
    const val HAS_LINK = "link"

    /** Filters taking a user id. */
    val userKeys = listOf(FROM, MENTIONS)

    val keys = listOf(FROM, MENTIONS, HAS, AFTER, BEFORE)

    /** `has:` values, mapped to the msgtype each selects. */
    val hasValues = mapOf(
            "image" to MessageType.MSGTYPE_IMAGE,
            "video" to MessageType.MSGTYPE_VIDEO,
            "audio" to MessageType.MSGTYPE_AUDIO,
            "file" to MessageType.MSGTYPE_FILE,
            "sticker" to EventType.STICKER,
            "poll" to EventType.POLL_START.stable,
    )

    /** Everything `has:` accepts, in the order the UI offers them. */
    val hasOptions = hasValues.keys + HAS_LINK
}
