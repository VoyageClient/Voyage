/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.richtext.linkify

import im.vector.lib.richtext.RichStyle
import im.vector.lib.richtext.SpanBuffer
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/** Matrix identifier detection, supplied by the SDK's `MatrixPatterns`. */
interface MatrixIdentifiers {
    val patterns: List<Regex>
    fun isPermalink(text: String): Boolean
    fun isIdentifier(text: String): Boolean
    val matrixToUrlBase: String
}

/**
 * Port of the Android link detection applied to every message body: `MatrixLinkify` (bare Matrix
 * identifiers and permalinks), then `VectorLinkify` over the framework's `Linkify` (web URLs, emails,
 * geo URIs, MSC references), then the removal of links over code and emotes.
 */
class Linkifier(private val matrix: MatrixIdentifiers) {

    fun linkify(text: SpanBuffer) {
        addMatrixLinks(text)
        VectorLinkify.addLinks(text, keepExistingUrlSpans = true)
        removeClickablesOver(text) { it is RichStyle.Emote }
        removeClickablesOver(text) { it is RichStyle.Code }
    }

    private fun addMatrixLinks(text: SpanBuffer) {
        if (text.isEmpty()) return
        val value = text.toString()
        for (pattern in listOf(AndroidPatterns.WEB_URL.toRegex()).plus(matrix.patterns)) {
            for (match in pattern.findAll(value)) {
                val start = match.range.first
                if (start == 0 || value[start - 1] != '/') {
                    val end = match.range.last + 1
                    var url = value.substring(match.range)
                    val isPermalink = matrix.isPermalink(url)
                    if (isPermalink || matrix.isIdentifier(url)) {
                        if (!isPermalink) url = matrix.matrixToUrlBase + url
                        text.setSpan(RichStyle.MatrixPermalink(url), start, end)
                    }
                }
            }
        }
    }

    // Android removes every ClickableSpan here, which includes spoilers (SpoilerSpan is clickable).
    private fun removeClickablesOver(text: SpanBuffer, covered: (RichStyle) -> Boolean) {
        val targets = text.allSpans().filter { covered(it.style) }
        if (targets.isEmpty()) return
        text.allSpans().filter { it.style.isClickable() }.forEach { link ->
            if (targets.any { link.start < it.end && it.start < link.end }) text.removeSpan(link)
        }
    }

    private fun RichStyle.isClickable() = this is RichStyle.Url || this is RichStyle.MatrixPermalink || this is RichStyle.Spoiler || this is RichStyle.Link
}

/** Port of the app's `VectorLinkify` on top of [AndroidLinkify]. */
internal object VectorLinkify {

    private const val MSC_PULL_URL = "https://github.com/matrix-org/matrix-spec-proposals/pull/"
    private val MSC: Pattern = Pattern.compile("\\bMSC(\\d{1,6})\\b", Pattern.CASE_INSENSITIVE)

    private const val LAT_OR_LONG_OR_ALT_NUMBER = "-?\\d+(?:\\.\\d+)?"
    private const val COORDINATE_SYSTEM = ";crs=[\\w-]+"
    private val GEO_URI: Pattern = Pattern.compile(
            "(?:geo:)?" +
                    "(" + LAT_OR_LONG_OR_ALT_NUMBER + ")" +
                    "," +
                    "(" + LAT_OR_LONG_OR_ALT_NUMBER + ")" +
                    "(?:" + "," + LAT_OR_LONG_OR_ALT_NUMBER + ")?" +
                    "(?:" + COORDINATE_SYSTEM + ")?" +
                    "(?:" + ";u=\\d+(?:\\.\\d+)?" + ")?" +
                    "(?:" +
                    ";[\\w-]+=(?:[\\w-_.!~*'()]|%[\\da-f][\\da-f])+" +
                    ")*", Pattern.CASE_INSENSITIVE
    )

    private class LinkSpec(val url: String, val start: Int, val end: Int, val important: Boolean = false)

    fun addLinks(text: SpanBuffer, keepExistingUrlSpans: Boolean) {
        val created = ArrayList<LinkSpec>()
        if (keepExistingUrlSpans) {
            forEachUrlSpan(text) { _, url, start, end -> created.add(LinkSpec(url, start, end, important = true)) }
        }
        AndroidLinkify.addLinks(text, webUrls = true, emailAddresses = true)
        forEachUrlSpan(text) { span, url, start, end ->
            text.removeSpan(span)
            if (url.startsWith("mailto:")) {
                val protocolLength = "mailto:".length
                if (start - protocolLength >= 0 && "mailto:" == text.substring(start - protocolLength, start)) {
                    created.add(LinkSpec(url, start - protocolLength, end))
                } else {
                    created.add(LinkSpec(url, start, end))
                }
                return@forEachUrlSpan
            }
            // `foo.com.fizzbuzz` matches only `foo.com`; don't highlight half a token.
            if (end + 1 < text.length && text[end] == '.' && text[end + 1].isLetterOrDigit()) return@forEachUrlSpan
            if (end < text.length - 1 && text[end] == '/') {
                created.add(LinkSpec("$url/", start, end + 1))
                return@forEachUrlSpan
            }
            if (text[end - 1] == ')') {
                var lbehind = end - 2
                var isFullyContained = 1
                while (lbehind > start) {
                    val char = text[lbehind]
                    if (char == '(') isFullyContained -= 1
                    if (char == ')') isFullyContained += 1
                    lbehind--
                }
                if (isFullyContained != 0) {
                    created.add(LinkSpec(text.substring(start, end - 1), start, end - 1))
                    return@forEachUrlSpan
                }
            }
            created.add(LinkSpec(url, start, end))
        }

        // Exclude short matches without the geo: prefix, e.g. don't highlight things like 1,2.
        AndroidLinkify.addLinks(text, GEO_URI, "geo:", arrayOf("geo:"), { s, start, end -> if (s[start] != 'g') end - start > 12 else true }, null)
        forEachUrlSpan(text) { span, url, start, end ->
            text.removeSpan(span)
            created.add(LinkSpec(url, start, end))
        }

        AndroidLinkify.addLinks(text, MSC, MSC_PULL_URL, null, null) { matcher, _ -> matcher.group(1)!! }
        forEachUrlSpan(text) { span, url, start, end ->
            text.removeSpan(span)
            created.add(LinkSpec(url, start, end))
        }

        pruneOverlaps(created)
        for (spec in created) text.setSpan(RichStyle.Url(spec.url), spec.start, spec.end)
    }

    private fun pruneOverlaps(links: ArrayList<LinkSpec>) {
        links.sortWith(COMPARATOR)
        var len = links.size
        var i = 0
        while (i < len - 1) {
            val a = links[i]
            val b = links[i + 1]
            var remove = -1
            if (b.start in a.start until a.end) {
                if (a.important != b.important) {
                    remove = if (a.important) i + 1 else i
                } else {
                    when {
                        b.end <= a.end -> remove = i + 1
                        a.end - a.start > b.end - b.start -> remove = i + 1
                        a.end - a.start < b.end - b.start -> remove = i
                    }
                }
                if (remove != -1) {
                    links.removeAt(remove)
                    len--
                    continue
                }
            }
            i++
        }
    }

    private val COMPARATOR = Comparator<LinkSpec> { a, b ->
        when {
            a.start < b.start -> -1
            a.start > b.start -> 1
            a.end < b.end -> 1
            a.end > b.end -> -1
            else -> 0
        }
    }

    private inline fun forEachUrlSpan(text: SpanBuffer, action: (span: SpanBuffer.Span, url: String, start: Int, end: Int) -> Unit) {
        for (span in text.allSpans()) {
            val url = when (val style = span.style) {
                is RichStyle.Link -> style.url
                is RichStyle.Url -> style.url
                else -> continue
            }
            action(span, url, span.start, span.end)
        }
    }
}

/** The parts of `android.text.util.Linkify` (API 28) the app uses, over a [SpanBuffer]. */
internal object AndroidLinkify {

    fun interface MatchFilter {
        fun acceptMatch(s: CharSequence, start: Int, end: Int): Boolean
    }

    fun interface TransformFilter {
        fun transformUrl(match: Matcher, url: String): String
    }

    private class LinkSpec(val url: String, val start: Int, val end: Int)

    private val urlMatchFilter = MatchFilter { s, start, _ -> start == 0 || s[start - 1] != '@' }

    fun addLinks(text: SpanBuffer, webUrls: Boolean, emailAddresses: Boolean): Boolean {
        text.allSpans().filter { it.style is RichStyle.Link || it.style is RichStyle.Url }.asReversed().forEach { text.removeSpan(it) }
        val links = ArrayList<LinkSpec>()
        if (webUrls) gatherLinks(links, text, AndroidPatterns.AUTOLINK_WEB_URL, arrayOf("http://", "https://", "rtsp://"), urlMatchFilter, null)
        if (emailAddresses) gatherLinks(links, text, AndroidPatterns.AUTOLINK_EMAIL_ADDRESS, arrayOf("mailto:"), null, null)
        pruneOverlaps(links)
        if (links.isEmpty()) return false
        for (link in links) text.setSpan(RichStyle.Url(link.url), link.start, link.end)
        return true
    }

    fun addLinks(text: SpanBuffer, pattern: Pattern, defaultScheme: String?, schemes: Array<String>?, matchFilter: MatchFilter?, transformFilter: TransformFilter?): Boolean {
        val schemesCopy = ArrayList<String>()
        schemesCopy.add((defaultScheme ?: "").lowercase(Locale.ROOT))
        schemes?.forEach { schemesCopy.add(it.lowercase(Locale.ROOT)) }
        var hasMatches = false
        val m = pattern.matcher(text.toString())
        while (m.find()) {
            val start = m.start()
            val end = m.end()
            val allowed = matchFilter?.acceptMatch(text, start, end) ?: true
            if (allowed) {
                val url = makeUrl(m.group(0)!!, schemesCopy.toTypedArray(), m, transformFilter)
                text.setSpan(RichStyle.Url(url), start, end)
                hasMatches = true
            }
        }
        return hasMatches
    }

    private fun makeUrl(url: String, prefixes: Array<String>, matcher: Matcher, filter: TransformFilter?): String {
        var result = url
        if (filter != null) result = filter.transformUrl(matcher, result)
        var hasPrefix = false
        for (prefix in prefixes) {
            if (result.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) {
                hasPrefix = true
                if (!result.regionMatches(0, prefix, 0, prefix.length, ignoreCase = false)) {
                    result = prefix + result.substring(prefix.length)
                }
                break
            }
        }
        if (!hasPrefix && prefixes.isNotEmpty()) result = prefixes[0] + result
        return result
    }

    private fun gatherLinks(links: ArrayList<LinkSpec>, s: SpanBuffer, pattern: Pattern, schemes: Array<String>, matchFilter: MatchFilter?, transformFilter: TransformFilter?) {
        val m = pattern.matcher(s.toString())
        while (m.find()) {
            val start = m.start()
            val end = m.end()
            if (matchFilter == null || matchFilter.acceptMatch(s, start, end)) {
                links.add(LinkSpec(makeUrl(m.group(0)!!, schemes, m, transformFilter), start, end))
            }
        }
    }

    private fun pruneOverlaps(links: ArrayList<LinkSpec>) {
        links.sortWith { a, b ->
            when {
                a.start < b.start -> -1
                a.start > b.start -> 1
                a.end < b.end -> 1
                a.end > b.end -> -1
                else -> 0
            }
        }
        var len = links.size
        var i = 0
        while (i < len - 1) {
            val a = links[i]
            val b = links[i + 1]
            var remove = -1
            if (a.start <= b.start && a.end > b.start) {
                if (b.end <= a.end) {
                    remove = i + 1
                } else if (a.end - a.start > b.end - b.start) {
                    remove = i + 1
                } else if (a.end - a.start < b.end - b.start) {
                    remove = i
                }
                if (remove != -1) {
                    links.removeAt(remove)
                    len--
                    continue
                }
            }
            i++
        }
    }
}
