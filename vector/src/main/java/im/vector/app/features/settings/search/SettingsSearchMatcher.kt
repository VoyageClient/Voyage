/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.search

import java.text.Normalizer
import java.util.Locale

class SettingsSearchMatcher(entries: List<SettingsSearchEntry>) {

    private class Prepared(val entry: SettingsSearchEntry, val title: String, val summary: String, val breadcrumb: String) {
        val titleWords = title.split(WORD_SEPARATOR)
    }

    private val prepared = entries.map {
        Prepared(it, normalize(it.title), normalize(it.summary.orEmpty()), normalize(it.breadcrumb.joinToString(" ")))
    }

    /** Entries containing every word of [query], best matches first, ties in settings order. */
    fun search(query: String): List<SettingsSearchEntry> {
        val normalizedQuery = normalize(query).trim()
        val tokens = normalizedQuery.split(WORD_SEPARATOR).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        return prepared
                .mapNotNull { p -> rank(p, normalizedQuery, tokens)?.let { it to p.entry } }
                .sortedBy { it.first }
                .map { it.second }
    }

    private fun rank(p: Prepared, query: String, tokens: List<String>): Int? {
        if (!tokens.all { it in p.title || it in p.summary || it in p.breadcrumb }) return null
        return when {
            p.title.startsWith(query) -> 0
            tokens.all { token -> p.titleWords.any { it.startsWith(token) } } -> 1
            tokens.all { it in p.title } -> 2
            tokens.all { it in p.title || it in p.summary } -> 3
            else -> 4
        }
    }

    companion object {
        private val WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")
        private val COMBINING_MARKS = Regex("\\p{Mn}+")

        fun normalize(text: String): String =
                COMBINING_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
    }
}
