/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.translation

/**
 * Swaps fragments that must survive machine translation verbatim (emoji, URLs, Matrix ids,
 * `:shortcodes:`, mention pills) for `{{n}}` placeholders, and puts them back afterwards.
 */
class TranslationExceptions private constructor(
        val text: String,
        private val protected: List<String>,
) {
    /** True when something other than placeholders/whitespace is left to translate. */
    val hasTranslatableText: Boolean
        get() = PLACEHOLDER.replace(text, "").isNotBlank()

    fun restore(translated: String): String {
        if (protected.isEmpty()) return translated
        val out = StringBuilder()
        var last = 0
        for (match in PLACEHOLDER.findAll(translated)) {
            out.append(translated, last, match.range.first)
            val item = protected.getOrNull(match.groupValues[1].toInt()) ?: match.value
            // Engines may glue a placeholder to the surrounding words; a link needs its whitespace
            // boundaries back or it stops being recognised as one.
            val isolate = ISOLATED.matches(item)
            if (isolate && out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
            out.append(item)
            last = match.range.last + 1
            if (isolate && last < translated.length && !translated[last].isWhitespace()) out.append(' ')
        }
        out.append(translated, last, translated.length)
        return out.toString()
    }

    companion object {
        // Engines sometimes pad or full-width the braces; accept those on the way back.
        val PLACEHOLDER = Regex("""[{｛]\s*[{｛]\s*(\d+)\s*[}｝]\s*[}｝]""")

        private val EMOJI = """(?:[\x{1F000}-\x{1FAFF}]|[\x{2600}-\x{27BF}]|[\x{2300}-\x{23FF}]|[\x{2B00}-\x{2BFF}]|\x{FE0F}|\x{200D}|\x{20E3}|[\x{1F3FB}-\x{1F3FF}])+"""
        private val URL = """(?:https?|matrix|mxc)://\S+"""
        private val MATRIX_ID = """[@#!][^\s:]+:[A-Za-z0-9.\-]+(?::\d+)?"""
        private val SHORTCODE = """:[A-Za-z0-9_+\-]+:"""

        private val RECEIVED = Regex("$URL|$MATRIX_ID|$SHORTCODE|$EMOJI")
        private val ISOLATED = Regex("(?:$URL|$MATRIX_ID)")

        // Outgoing text is tokenised by word so a leading @/#/:/! keeps the whole word, matching the
        // plugin's wordStart exceptions.
        private val SENT_WORD = Regex("""^(?:[@#:!].+|$URL|$EMOJI)$""")
        private val SENT_INLINE = Regex("$URL|$EMOJI")

        fun forReceived(text: String): TranslationExceptions = protect(text, RECEIVED, emptyList())

        /**
         * [pills] are pre-extracted placeholder texts (e.g. mention pills) already substituted into
         * [text] as `{{0}}`…`{{n-1}}`; further exceptions continue the numbering.
         */
        fun forSent(text: String, pills: List<String> = emptyList()): TranslationExceptions {
            val protected = pills.toMutableList()
            val out = StringBuilder()
            var index = 0
            val wordRegex = Regex("""\S+""")
            for (match in wordRegex.findAll(text)) {
                out.append(text, index, match.range.first)
                val word = match.value
                if (!PLACEHOLDER.containsMatchIn(word) && SENT_WORD.matches(word)) {
                    out.append("{{").append(protected.size).append("}}")
                    protected.add(word)
                } else {
                    out.append(word)
                }
                index = match.range.last + 1
            }
            out.append(text, index, text.length)
            return protect(out.toString(), SENT_INLINE, protected)
        }

        private fun protect(text: String, regex: Regex, seed: List<String>): TranslationExceptions {
            val protected = seed.toMutableList()
            val replaced = regex.replace(text) { match ->
                if (PLACEHOLDER.matches(match.value)) {
                    match.value
                } else {
                    protected.add(match.value)
                    "{{${protected.size - 1}}}"
                }
            }
            return TranslationExceptions(replaced, protected)
        }
    }
}
