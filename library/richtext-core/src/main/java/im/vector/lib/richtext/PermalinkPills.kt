/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.richtext

const val NOTIFY_EVERYONE = "@room"

/** Port of the Android `EventTextRenderer`: pills for permalinks in the text body and for `@room`. */
class PermalinkPills(private val resolver: PillResolver) {

    // Matrix permalinks use a `…/#/<id>[/<eventId>]` fragment whose `!`, `$`, `:` chars the URL pattern
    // truncates, so match the whole `#/…` run up to whitespace instead.
    private val permalinkRegex = Regex("""https?://[^\s/]+/#/\S+""")

    fun apply(text: SpanBuffer) {
        addPermalinkPills(text)
        if (text.toString().contains(NOTIFY_EVERYONE)) addNotifyEveryonePills(text)
    }

    private fun addNotifyEveryonePills(text: SpanBuffer) {
        val everyone = resolver.notifyEveryone() ?: return
        fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'
        val codeSpans = text.spansOf<RichStyle.Code>()
        val found = ArrayList<Int>()
        val value = text.toString()
        var index = value.indexOf(NOTIFY_EVERYONE, 0)
        while (index >= 0) {
            val end = index + NOTIFY_EVERYONE.length
            val boundaryBefore = index == 0 || !isWordChar(value[index - 1])
            val boundaryAfter = end == value.length || !isWordChar(value[end])
            val inCode = codeSpans.any { it.start < end && index < it.end }
            if (boundaryBefore && boundaryAfter && !inCode) found.add(index)
            index = value.indexOf(NOTIFY_EVERYONE, end)
        }
        found.asReversed().forEach { text.setPillSpan(RichStyle.Pill(everyone), it, it + NOTIFY_EVERYONE.length) }
    }

    private class Placement(val target: PillTarget, val start: Int, val end: Int, val url: String)

    private fun addPermalinkPills(text: SpanBuffer) {
        val placements = ArrayList<Placement>()
        val codeSpans = text.spansOf<RichStyle.Code>()
        fun inCode(start: Int, end: Int) = codeSpans.any { it.start < end && start < it.end }
        val existingPills = text.spansOf<RichStyle.Pill>()
        for (span in text.allSpans()) {
            val url = when (val style = span.style) {
                is RichStyle.Link -> style.url
                is RichStyle.Url -> style.url
                else -> continue
            }
            val start = span.start
            val end = span.end
            if (inCode(start, end)) continue
            if (existingPills.any { it.start < end && start < it.end }) continue
            val target = resolver.resolvePermalink(url) ?: continue
            placements.add(Placement(target, start, end, url))
        }
        val value = text.toString()
        for (match in permalinkRegex.findAll(value)) {
            val start = match.range.first
            var end = match.range.last + 1
            while (end > start && value[end - 1] in TRAILING_URL_PUNCTUATION) end--
            if (inCode(start, end)) continue
            if (placements.any { it.start < end && start < it.end }) continue
            val url = value.substring(start, end)
            val target = resolver.resolvePermalink(url) ?: continue
            placements.add(Placement(target, start, end, url))
        }
        placements.sortedByDescending { it.start }.forEach {
            text.setPillSpan(RichStyle.Pill(it.target), it.start, it.end)
            // The collapse dropped the underlying link; re-add one so taps still open the permalink.
            text.setSpan(RichStyle.Url(it.url), it.start, it.start + PILL_PLACEHOLDER.length)
        }
    }

    companion object {
        private const val TRAILING_URL_PUNCTUATION = ".,;:!?)]}>\"'"
    }
}
