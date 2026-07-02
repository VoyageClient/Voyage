/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store


/**
 * Codec for `MutableList<String>` columns stored as a single newline-joined TEXT value. An empty list
 * is the empty string; a list whose elements could themselves contain newlines is not supported (none
 * of the migrated columns — room/user ids, server names, aliases — ever do).
 */
private const val LIST_SEPARATOR = "\n"

internal fun List<String>.joinToColumn(): String = joinToString(LIST_SEPARATOR)

internal fun String?.splitToList(): List<String> = if (isNullOrEmpty()) emptyList() else split(LIST_SEPARATOR)

internal fun String?.splitToRealmList(): MutableList<String> = ArrayList<String>().apply { addAll(splitToList()) }

/** SQLite caps bound variables at 999 per statement; run an IN-list query in chunks and concatenate. */
internal fun <T, R> Collection<T>.flatMapInChunks(fetch: (List<T>) -> List<R>): List<R> = when {
    isEmpty() -> emptyList()
    size <= 500 -> fetch(toList())
    else -> chunked(500).flatMap(fetch)
}

/**
 * The legacy filter constants in [org.matrix.android.sdk.internal.database.query.TimelineEventFilter]
 * are authored for Realm's `LIKE`, whose wildcards are glob-style (`*` = any run, `?` = any char). SQL
 * `LIKE` uses `%`/`_`, so a glob pattern passed straight through matches nothing. Translate it, escaping
 * any literal `%`/`_` the source pattern happens to contain.
 */
internal fun String.globToSqlLike(): String = buildString {
    for (c in this@globToSqlLike) {
        when (c) {
            '*' -> append('%')
            '?' -> append('_')
            '%', '_' -> append('\\').append(c)
            else -> append(c)
        }
    }
}
