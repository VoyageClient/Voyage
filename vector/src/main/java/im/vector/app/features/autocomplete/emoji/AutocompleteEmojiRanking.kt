/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.autocomplete.emoji

import im.vector.app.features.imagepack.EmojiShortNames
import im.vector.app.features.imagepack.EmoteShortcodeProcessor

/**
 * Orders `:` suggestions, emotes and unicode emoji together: 1, +4 for an exact name, +2 for a whole-word
 * match, +1 for a prefix, times frecency/100 for anything used before (see FrecencyDataSource.score); ties by name.
 */
object AutocompleteEmojiRanking {

    /** A substring match ignoring case, spaces and underscores. */
    fun matches(shortcode: String, query: String): Boolean {
        val needle = normalize(query).replace(" ", "").replace("_", "")
        return shortcode.replace("_", "").contains(needle, ignoreCase = true)
    }

    fun sort(rows: List<AutocompleteEmojiData>, query: String, frecency: (AutocompleteEmojiData) -> Int?): List<AutocompleteEmojiData> {
        val scorer = Scorer(normalize(query))
        return rows
                .map { row ->
                    val score = scorer.score(row).toDouble()
                    row to (frecency(row)?.let { score * it / 100 } ?: score)
                }
                .sortedWith(compareByDescending<Pair<AutocompleteEmojiData, Double>> { it.second }.thenBy { displayName(it.first) })
                .map { it.first }
    }

    private fun normalize(query: String) = query.trim().removeSuffix(":").removeSuffix(EmojiShortNames.UNICODE_SUFFIX)

    private fun displayName(row: AutocompleteEmojiData) = when (row) {
        is AutocompleteEmojiData.Emoji -> row.shortcode
        is AutocompleteEmojiData.Emote -> row.image.shortcode
    }

    private class Scorer(query: String) {
        private val lowerQuery = query.lowercase()
        private val wholeWord = Regex("(^|_|[A-Z])" + Regex.escape(lowerQuery) + "s?([A-Z]|_|$)")

        fun score(row: AutocompleteEmojiData): Int = when (row) {
            // A disambiguation suffix isn't part of the name, so `fire/cats` scores as `fire`.
            is AutocompleteEmojiData.Emote -> score(EmoteShortcodeProcessor.baseOf(row.image.shortcode))
            // By its best shortcode, so a typed alias ranks too. Emoji found only through their keywords or
            // Unicode name rank below every shortcode match.
            is AutocompleteEmojiData.Emoji -> row.emojiItem.shortcodes
                    .filter { matches(it, lowerQuery) }
                    .maxOfOrNull { score(it) } ?: 0
        }

        private fun score(name: String): Int {
            val lower = name.lowercase()
            var score = 1
            if (lower == lowerQuery) score += 4
            if (wholeWord.containsMatchIn(lower) || wholeWord.containsMatchIn(name)) score += 2
            if (name.startsWith(lowerQuery, ignoreCase = true)) score += 1
            return score
        }
    }
}
