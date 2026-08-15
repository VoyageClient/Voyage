/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.pushrules

import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.internal.di.MoshiProvider

/** The event as the push rules see it: its JSON, with a decrypted payload laid over the top. */
internal fun Event.pushRuleJson(): Map<*, *>? {
    val rawJson = MoshiProvider.providesMoshi().adapter(Event::class.java).toJsonValue(this) as? Map<*, *> ?: return null
    val decrypted = mxDecryptionResult?.payload ?: return rawJson
    return rawJson.toMutableMap().apply { putAll(decrypted) }
}

/**
 * The value at a dot-separated path, e.g. `content.m\.mentions.room`. A backslash escapes the
 * character after it, which is how a key containing a literal dot is written.
 */
internal fun Map<*, *>.propertyAtPath(path: String): Any? {
    var current: Any? = this
    splitEscapedPath(path).forEach { segment ->
        current = (current as? Map<*, *>)?.get(segment) ?: return null
    }
    return current
}

private fun splitEscapedPath(path: String): List<String> {
    val segments = mutableListOf<String>()
    val segment = StringBuilder()
    var escaped = false
    path.forEach { c ->
        when {
            escaped -> {
                segment.append(c)
                escaped = false
            }
            c == '\\' -> escaped = true
            c == '.' -> {
                segments.add(segment.toString())
                segment.clear()
            }
            else -> segment.append(c)
        }
    }
    segments.add(segment.toString())
    return segments
}

/** JSON numbers arrive as Double whatever the rule was written with, so compare them as such. */
internal fun jsonValueEquals(actual: Any?, expected: Any?): Boolean = when {
    actual is Number && expected is Number -> actual.toDouble() == expected.toDouble()
    else -> actual == expected
}
