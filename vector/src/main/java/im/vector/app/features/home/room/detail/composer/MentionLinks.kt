/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import im.vector.app.features.command.Command
import org.matrix.android.sdk.api.MatrixPatterns
import org.matrix.android.sdk.api.session.room.send.MatrixItemSpan
import org.matrix.android.sdk.api.util.MatrixItem

private val MENTION_ANCHOR = Regex(
        """<a\s+[^>]*href="https://matrix\.to/#/(@[^"?]+)[^"]*"[^>]*>([^<]*)</a>""",
        RegexOption.IGNORE_CASE
)

// Room pills as well as user ones, for the clipboard: pasting either back into a composer pills it again.
private val ANY_MENTION_ANCHOR = Regex(
        """<a\s+[^>]*href="https://matrix\.to/#/([@#][^"?]+)[^"]*"[^>]*>([^<]*)</a>""",
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
    val out = StringBuilder()
    var cursor = 0
    forEachMention(body, formattedBody, displayNamesOf) { start, name, userId ->
        out.append(body, cursor, start)
        out.append('[').append(name).append("](https://matrix.to/#/").append(userId).append(')')
        cursor = start + name.length
    }
    if (cursor == 0) return body
    out.append(body, cursor, body.length)
    return out.toString()
}

/**
 * Rewrites each mention of [body] as the bare id it points at, reading the targets from [formattedBody] —
 * what "Copy" puts on the clipboard, so a message that names the people it mentions pastes into a
 * composer as something that mentions them again.
 */
fun spliceMentionIds(body: String, formattedBody: String?): String {
    val out = StringBuilder()
    var cursor = 0
    forEachMention(body, formattedBody, { emptyList() }, ANY_MENTION_ANCHOR) { start, name, id ->
        out.append(body, cursor, start).append(id)
        cursor = start + name.length
    }
    if (cursor == 0) return body
    out.append(body, cursor, body.length)
    return out.toString()
}

/**
 * [spliceMentionLinks], but tagging the located mentions with [MatrixItemSpan]s instead of rewriting
 * them as markdown — for text sent straight to the SDK without a composer round-trip (e.g. a sed
 * substitution), where a literal `[name](link)` would leak into the plain body.
 */
fun spliceMentionSpans(
        body: String,
        formattedBody: String?,
        displayNamesOf: (String) -> List<String> = { emptyList() },
): CharSequence {
    var spannable: SpannableStringBuilder? = null
    forEachMention(body, formattedBody, displayNamesOf) { start, name, userId ->
        val target = spannable ?: SpannableStringBuilder(body).also { spannable = it }
        target.setSpan(
                SendableMentionSpan(MatrixItem.UserItem(userId, name), name),
                start,
                start + name.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    return spannable ?: body
}

/**
 * The ranges of [text] holding a mention the composer should show as a pill — a user id, a room
 * alias or `@room` — skipping any a pill already covers. [requireTerminator] is what keeps the
 * composer off one still being typed: it then only takes a mention that something follows and that
 * [caret] has moved past.
 */
fun findMentions(text: CharSequence, caret: Int = -1, requireTerminator: Boolean = false): List<IntRange> {
    if (sendsLiteralText(text)) return emptyList()
    val spanned = text as? Spanned
    val existing = spanned?.getSpans(0, text.length, MatrixItemSpan::class.java).orEmpty()
    val code = codeRegions(text)
    val found = mutableListOf<IntRange>()

    fun take(start: Int, matchEnd: Int, isValid: (String) -> Boolean) {
        // The domain pattern swallows the sentence punctuation a mention can end up next to; trim it
        // off the range, but judge the boundary at the end of the whole match — the trimmed end of a
        // half-typed "@user:example." sits on a dot that would read as a terminator.
        var end = matchEnd
        while (end > start && text[end - 1] in TRAILING_PUNCTUATION) end--
        // Anything but a separator around it means it is part of something else — a permalink, an
        // email address, a markdown link target.
        if (!isMentionStart(text.getOrNull(start - 1))) return
        if (matchEnd < text.length && !isMentionEnd(text[matchEnd])) return
        if (requireTerminator && (matchEnd == text.length || caret == matchEnd)) return
        if (existing.any { spanned!!.getSpanStart(it) < end && spanned.getSpanEnd(it) > start }) return
        if (found.any { it.first < end && it.last + 1 > start }) return
        if (code.any { it.first < end && it.last + 1 > start }) return
        if (!isValid(text.substring(start, end))) return
        found += start until end
    }

    MatrixPatterns.PATTERN_CONTAIN_MATRIX_USER_IDENTIFIER.findAll(text).forEach {
        take(it.range.first, it.range.last + 1, MatrixPatterns::isUserId)
    }
    MatrixPatterns.PATTERN_CONTAIN_MATRIX_ALIAS.findAll(text).forEach {
        take(it.range.first, it.range.last + 1, MatrixPatterns::isRoomAlias)
    }
    var at = text.indexOf(MatrixItem.NOTIFY_EVERYONE)
    while (at >= 0) {
        take(at, at + MatrixItem.NOTIFY_EVERYONE.length) { it == MatrixItem.NOTIFY_EVERYONE }
        at = text.indexOf(MatrixItem.NOTIFY_EVERYONE, at + 1)
    }
    return found.sortedBy { it.first }
}

/**
 * Tags the mentions [findMentions] finds with a [MatrixItemSpan], so they send as mentions. The
 * composer pills them as they are written; this catches the one typed at the very end of a message,
 * which nothing has finished off yet. An alias only counts where [resolveAlias] knows the room, and
 * `@room` needs no span at all — it notifies from the plain text the body already carries.
 */
fun CharSequence.pillifyRemainingMentions(resolveAlias: (String) -> MatrixItem?): CharSequence {
    val mentions = findMentions(this).mapNotNull { range ->
        val mention = substring(range.first, range.last + 1)
        val item = when {
            MatrixPatterns.isUserId(mention) -> MatrixItem.UserItem(mention)
            MatrixPatterns.isRoomAlias(mention) -> resolveAlias(mention)
            else -> null
        }
        item?.let { Triple(range, it, mention) }
    }
    val escapes = escapedMentionBackslashes(this)
    if (mentions.isEmpty() && escapes.isEmpty()) return this
    val out = SpannableStringBuilder(this)
    mentions.forEach { (range, item, mention) ->
        out.setSpan(SendableMentionSpan(item, mention), range.first, range.last + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    // Last first, so the earlier positions still hold.
    escapes.asReversed().forEach { out.delete(it, it + 1) }
    return out
}

/**
 * The backslashes that keep a mention from being one. They are escape syntax, not something the
 * writer means to say, so the body carries the mention alone — as a message that never pilled it.
 */
private fun escapedMentionBackslashes(text: CharSequence): List<Int> {
    if (!text.contains('\\')) return emptyList()
    val found = mutableListOf<Int>()
    var i = 0
    while (i < text.length) {
        if (text[i] != '\\') {
            i++
            continue
        }
        // A doubled backslash is itself escaped, so the second one is a literal character.
        if (i + 1 < text.length && text[i + 1] == '\\') {
            i += 2
            continue
        }
        if (isMentionStart(text.getOrNull(i - 1)) && startsMention(text, i + 1)) found += i
        i++
    }
    return found
}

private fun startsMention(text: CharSequence, at: Int): Boolean {
    if (at >= text.length) return false
    if (text.regionMatches(at, MatrixItem.NOTIFY_EVERYONE, 0, MatrixItem.NOTIFY_EVERYONE.length)) return true
    return MatrixPatterns.PATTERN_CONTAIN_MATRIX_USER_IDENTIFIER.find(text, at)?.range?.first == at ||
            MatrixPatterns.PATTERN_CONTAIN_MATRIX_ALIAS.find(text, at)?.range?.first == at
}

// /plain and /html send the composer text as it literally reads, so a mention in one stays plain text.
private fun sendsLiteralText(text: CharSequence): Boolean {
    if (text.isEmpty() || text[0] != '/') return false
    val firstWord = text.subSequence(0, text.indexOfFirst { it.isWhitespace() }.takeIf { it > 0 } ?: text.length)
    return Command.PLAIN.matches(firstWord) || Command.HTML.matches(firstWord)
}

/**
 * The stretches of [text] that markdown reads as code — fenced blocks and inline spans — where a
 * mention is a piece of code being quoted rather than someone being mentioned. A block or span the
 * writer has not closed yet runs to the end of the text (resp. of its line), so a mention typed
 * inside one is left alone while it is still being written.
 */
private fun codeRegions(text: CharSequence): List<IntRange> {
    if (!text.contains('`')) return emptyList()
    val regions = mutableListOf<IntRange>()
    var i = 0
    while (i < text.length) {
        if (text[i] != '`') {
            i++
            continue
        }
        var runEnd = i
        while (runEnd < text.length && text[runEnd] == '`') runEnd++
        val fence = runEnd - i >= 3 && text.subSequence(lineStart(text, i), i).isBlank()
        val closeAt = if (fence) nextFence(text, runEnd, runEnd - i) else runOfBackticks(text, runEnd, runEnd - i)
        val end = closeAt ?: if (fence) text.length else lineEnd(text, runEnd)
        regions += i until end
        i = end
    }
    return regions
}

private fun lineStart(text: CharSequence, from: Int): Int {
    var at = from
    while (at > 0 && text[at - 1] != '\n') at--
    return at
}

private fun lineEnd(text: CharSequence, from: Int): Int {
    var at = from
    while (at < text.length && text[at] != '\n') at++
    return at
}

/** The end of the fence line that closes a block opened with [length] backticks, if there is one. */
private fun nextFence(text: CharSequence, from: Int, length: Int): Int? {
    var at = from
    while (at < text.length) {
        val start = lineEnd(text, at).let { if (it >= text.length) return null else it + 1 }
        var run = start
        while (run < text.length && text[run] == '`') run++
        if (run - start >= length) return lineEnd(text, run)
        at = start
    }
    return null
}

/** The end of the next run of exactly [length] backticks, which closes an inline span. */
private fun runOfBackticks(text: CharSequence, from: Int, length: Int): Int? {
    var at = from
    while (at < text.length) {
        if (text[at] != '`') {
            at++
            continue
        }
        var run = at
        while (run < text.length && text[run] == '`') run++
        if (run - at == length) return run
        at = run
    }
    return null
}

private const val TRAILING_PUNCTUATION = ".-"

private fun isMentionStart(before: Char?) = before == null || before.isWhitespace() || before in "([{<\"'"

private fun isMentionEnd(after: Char) = !after.isLetterOrDigit() && after != '_'

private class SendableMentionSpan(
        override val matrixItem: MatrixItem,
        override val bodyText: String,
) : MatrixItemSpan

private inline fun forEachMention(
        body: String,
        formattedBody: String?,
        displayNamesOf: (String) -> List<String>,
        anchors: Regex = MENTION_ANCHOR,
        onMatch: (start: Int, name: String, userId: String) -> Unit,
) {
    if (formattedBody == null || body.isEmpty() || !formattedBody.contains("matrix.to/#/")) return
    var cursor = 0
    anchors.findAll(formattedBody).forEach { match ->
        val userId = match.groupValues[1]
        val label = match.groupValues[2].unescapeHtml()
        val candidates = (listOf(label, userId) + displayNamesOf(userId))
                // A name carrying markdown link syntax can't round-trip through the composer.
                .filter { it.isNotBlank() && !it.contains('[') && !it.contains(']') }
        val at = candidates.firstNotNullOfOrNull { body.wordIndexOf(it, cursor)?.to(it) } ?: return@forEach
        onMatch(at.first, at.second, userId)
        cursor = at.first + at.second.length
    }
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
