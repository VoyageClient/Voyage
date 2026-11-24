/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.permalinks

/**
 * Builds MSC2312 matrix URIs, which MSC4487 asks for wherever a message references a user, room or event.
 * Links meant for outside Matrix (copy link, share) stay matrix.to.
 */
object MatrixUriBuilder {

    /** A link to a user, room alias or room id, or null when [id] has no matrix URI form. */
    fun build(id: String): String? = entity(id)?.let { PermalinkService.MATRIX_URI_SCHEME_PREFIX + it }

    /** The href of a mention pill to [id]; an id with no matrix URI form keeps its matrix.to link. */
    fun mentionLink(id: String): String = build(id) ?: PermalinkService.MATRIX_TO_URL_BASE + id

    /** A link to [eventId] in [roomIdOrAlias], or null when that is not a room. */
    fun build(roomIdOrAlias: String, eventId: String, viaServers: List<String> = emptyList()): String? {
        if (roomIdOrAlias.startsWith('@') || eventId.length < 2) return null
        val room = entity(roomIdOrAlias) ?: return null
        return PermalinkService.MATRIX_URI_SCHEME_PREFIX + room + "/e/" + encode(eventId.removePrefix("$")) + via(viaServers)
    }

    private fun entity(id: String): String? {
        val type = when (id.firstOrNull()) {
            '@' -> "u"
            '#' -> "r"
            '!' -> "roomid"
            else -> return null
        }
        if (id.length < 2) return null
        return type + "/" + encode(id.substring(1))
    }

    private fun via(servers: List<String>): String =
            servers.distinct().joinToString("&") { "via=" + encode(it) }.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()

    // Encoding all but unreserved, ':' and '@' is always valid, and keeps the link whole as a markdown
    // destination or an HTML attribute.
    private fun encode(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val c = byte.toInt() and 0xFF
            if (c < 0x80 && (c.toChar() in 'A'..'Z' || c.toChar() in 'a'..'z' || c.toChar() in '0'..'9' || c.toChar() in SAFE)) {
                append(c.toChar())
            } else {
                append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
            }
        }
    }

    private const val SAFE = "-._~:@"
    private const val HEX = "0123456789ABCDEF"
}
