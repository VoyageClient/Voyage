/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.spaces.tags

import im.vector.app.core.resources.StringProvider
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.room.model.tag.RoomTag

const val USER_TAG_PREFIX = "u."

fun displayNameForTag(stringProvider: StringProvider, tagName: String): String {
    return when (tagName) {
        RoomTag.ROOM_TAG_FAVOURITE -> stringProvider.getString(CommonStrings.tag_favourites)
        RoomTag.ROOM_TAG_LOW_PRIORITY -> stringProvider.getString(CommonStrings.tag_low_priority)
        RoomTag.ROOM_TAG_SERVER_NOTICE -> stringProvider.getString(CommonStrings.tag_server_notice)
        else -> tagName.removePrefix(USER_TAG_PREFIX)
    }
}

fun tagSortKey(tagName: String): String {
    return when (tagName) {
        RoomTag.ROOM_TAG_FAVOURITE -> "0"
        RoomTag.ROOM_TAG_LOW_PRIORITY -> "1"
        RoomTag.ROOM_TAG_SERVER_NOTICE -> "2"
        else -> "3" + tagName.removePrefix(USER_TAG_PREFIX).lowercase()
    }
}

fun normaliseUserTag(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    return if (trimmed.startsWith("m.") || trimmed.startsWith(USER_TAG_PREFIX)) trimmed else USER_TAG_PREFIX + trimmed
}
