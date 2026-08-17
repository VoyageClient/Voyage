/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

private val MENTION_ANCHOR = Regex(
        """<a\s+[^>]*href="https://matrix\.to/#/(@[^"?]+)[^"]*"[^>]*>([^<]*)</a>""",
        RegexOption.IGNORE_CASE
)

/**
 * Rewrites the user mentions of [body] as markdown permalinks, reading each mention's target from the
 * same message's [formattedBody]. A mention only exists as an anchor in the HTML — the plain body
 * spells it out as a name — so the composer needs this to rebuild the pills when editing.
 *
 * The anchor's own label is not enough to locate the mention: the two bodies need not call it the
 * same thing, and plenty of messages in a room predate any given client agreeing that they should —
 * a link text of the bare user id over a body naming the sender is common. So each mention is looked
 * up under every name it plausibly goes by, and one that matches none of them is left as plain text
 * rather than risking a mangled body.
 */
fun spliceMentionLinks(
        body: String,
        formattedBody: String?,
        displayNamesOf: (String) -> List<String> = { emptyList() },
): String {
    if (formattedBody == null || body.isEmpty() || !formattedBody.contains("matrix.to/#/@")) return body
    val out = StringBuilder()
    var cursor = 0
    MENTION_ANCHOR.findAll(formattedBody).forEach { match ->
        val userId = match.groupValues[1]
        val label = match.groupValues[2].unescapeHtml()
        val candidates = (listOf(label, userId) + displayNamesOf(userId))
                // A name carrying markdown link syntax can't round-trip through the composer.
                .filter { it.isNotBlank() && !it.contains('[') && !it.contains(']') }
        val at = candidates.firstNotNullOfOrNull { body.wordIndexOf(it, cursor)?.to(it) } ?: return@forEach
        out.append(body, cursor, at.first)
        out.append('[').append(at.second).append("](https://matrix.to/#/").append(userId).append(')')
        cursor = at.first + at.second.length
    }
    if (cursor == 0) return body
    out.append(body, cursor, body.length)
    return out.toString()
}

// Plain indexOf would let a short display name match inside a longer word.
private fun String.wordIndexOf(name: String, from: Int): Int? {
    var at = indexOf(name, from)
    while (at >= 0) {
        val before = getOrNull(at - 1)
        val after = getOrNull(at + name.length)
        if (!before.isNamePart() && !after.isNamePart()) return at
        at = indexOf(name, at + 1)
    }
    return null
}

private fun Char?.isNamePart() = this != null && (isLetterOrDigit() || this == '_')

private fun String.unescapeHtml(): String {
    if (!contains('&')) return this
    return replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
}
