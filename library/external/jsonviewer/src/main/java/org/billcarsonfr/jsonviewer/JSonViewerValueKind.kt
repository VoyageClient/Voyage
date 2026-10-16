/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.billcarsonfr.jsonviewer

import java.math.BigDecimal

enum class JSonViewerValueKind {
    USER_ID,
    ROOM_ID,
    EVENT_ID,
    TIMESTAMP
}

/** Receives double taps on ID values; timestamps are toggled by the viewer itself and never reach it. */
fun interface JSonViewerValueListener {
    fun onJsonValueDoubleTapped(kind: JSonViewerValueKind, value: String)
}

// Identifier grammar from https://spec.matrix.org/latest/appendices/
internal object JSonViewerValueDetector {

    private const val SERVER_NAME = "(?:[0-9]{1,3}(?:\\.[0-9]{1,3}){3}|\\[[0-9A-Fa-f:.]{2,45}\\]|[0-9A-Za-z.-]{1,255})(?::[0-9]{1,5})?"

    // Historical localparts and pre-v3 opaque IDs may hold any code point but ':' and NUL.
    private const val OPAQUE = "[^:\\u0000]+"

    // Unpadded base64 of a sha256 reference hash: standard alphabet in room v3, URL-safe from v4.
    private const val HASH = "(?:[A-Za-z0-9+/]{43}|[A-Za-z0-9_-]{43})"

    private val USER_ID = Regex("@$OPAQUE:$SERVER_NAME")

    // v12 room IDs are the create event's ID with a '!' sigil, so they carry no server name.
    private val ROOM_ID = Regex("!(?:$OPAQUE:$SERVER_NAME|[A-Za-z0-9_-]{43})")
    private val EVENT_ID = Regex("\\$(?:$OPAQUE:$SERVER_NAME|$HASH)")

    private const val MAX_ID_BYTES = 255

    // 2001-09-09 to 2286-11-20: every 13-digit millisecond count, so seconds and small counters don't match.
    private const val MIN_TIMESTAMP_MS = 1_000_000_000_000L
    private const val MAX_TIMESTAMP_MS = 9_999_999_999_999L

    fun detect(type: JSONType, value: String): JSonViewerValueKind? = when (type) {
        JSONType.STRING -> detectId(value)
        JSONType.NUMBER -> JSonViewerValueKind.TIMESTAMP.takeIf { timestampMs(value) != null }
        else -> null
    }

    fun timestampMs(value: String): Long? {
        val number = value.toBigDecimalOrNull() ?: return null
        val ms = try {
            number.longValueExact()
        } catch (e: ArithmeticException) {
            return null
        }
        return ms.takeIf { it in MIN_TIMESTAMP_MS..MAX_TIMESTAMP_MS && number.compareTo(BigDecimal.valueOf(it)) == 0 }
    }

    private fun detectId(value: String): JSonViewerValueKind? {
        if (value.length < 2 || value.toByteArray(Charsets.UTF_8).size > MAX_ID_BYTES) return null
        return when (value[0]) {
            '@' -> JSonViewerValueKind.USER_ID.takeIf { USER_ID.matches(value) }
            '!' -> JSonViewerValueKind.ROOM_ID.takeIf { ROOM_ID.matches(value) }
            '$' -> JSonViewerValueKind.EVENT_ID.takeIf { EVENT_ID.matches(value) }
            else -> null
        }
    }
}
