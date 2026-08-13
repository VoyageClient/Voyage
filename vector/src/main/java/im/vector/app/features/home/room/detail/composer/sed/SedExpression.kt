/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer.sed

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

const val SED_MATCH_TIMEOUT_MS = 500L

sealed interface SedParseResult {
    object NotSed : SedParseResult
    data class Invalid(val reason: String) : SedParseResult
    data class Parsed(val expression: SedExpression) : SedParseResult
}

class SedExpression(
        private val find: Pattern,
        private val replacement: String,
        private val global: Boolean,
        val highlight: Boolean,
) {
    /** Returns the substituted text, or null when the pattern does not match [body]. */
    fun apply(body: String, deadline: Long = System.currentTimeMillis() + SED_MATCH_TIMEOUT_MS): String? {
        val matcher = find.matcher(DeadlinedCharSequence(body, deadline))
        if (!matcher.find()) return null
        val out = StringBuffer()
        do {
            matcher.appendReplacement(out, replacement)
        } while (global && matcher.find())
        matcher.appendTail(out)
        return out.toString()
    }
}

fun parseSed(text: CharSequence): SedParseResult {
    val raw = text.toString().trim()
    if (raw.length < 4 || raw[0] != 's') return SedParseResult.NotSed
    val separator = raw[1]
    if (separator != '/' && separator != '#') return SedParseResult.NotSed

    val pattern = readUntilSeparator(raw, 2, separator) ?: return SedParseResult.NotSed
    if (pattern.value.isEmpty()) return SedParseResult.Invalid("empty search pattern")
    // The trailing delimiter is optional; without it everything left is the replacement and there are no flags.
    val replacement = readUntilSeparator(raw, pattern.end, separator)
    val rawReplacement = replacement?.value ?: raw.substring(pattern.end)
    val rawFlags = replacement?.let { raw.substring(it.end) }.orEmpty()

    var flags = Pattern.UNICODE_CASE
    var global = false
    var highlight = true
    for (flag in rawFlags) {
        when (flag) {
            // Android's regex rejects UNICODE_CHARACTER_CLASS, so \w and friends are ASCII-only
            // regardless — which is what this flag asks for.
            'a' -> Unit
            'i' -> flags = flags or Pattern.CASE_INSENSITIVE
            'm' -> flags = flags or Pattern.MULTILINE
            's' -> flags = flags or Pattern.DOTALL
            'g' -> global = true
            'u' -> highlight = false
            else -> return SedParseResult.Invalid("unknown flag '$flag'")
        }
    }

    val compiled = try {
        Pattern.compile(pattern.value, flags)
    } catch (failure: PatternSyntaxException) {
        return SedParseResult.Invalid(failure.description ?: failure.message.orEmpty())
    }
    val highestGroup = highestGroupReference(rawReplacement)
    if (highestGroup > compiled.matcher("").groupCount()) {
        return SedParseResult.Invalid("no group \\$highestGroup in the search pattern")
    }
    return SedParseResult.Parsed(
            SedExpression(
                    find = compiled,
                    replacement = toJavaReplacement(rawReplacement),
                    global = global,
                    highlight = highlight,
            )
    )
}

/** Wraps the changed span of [new] in `<u>`, escaping both sides for HTML. */
fun highlightDiff(old: String, new: String): String {
    var prefix = 0
    val maxPrefix = minOf(old.length, new.length)
    while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    val maxSuffix = maxPrefix - prefix
    while (suffix < maxSuffix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++

    val changed = new.substring(prefix, new.length - suffix)
    if (changed.isEmpty()) return escapeHtml(new)
    return escapeHtml(new.substring(0, prefix)) +
            "<u>" + escapeHtml(changed) + "</u>" +
            escapeHtml(new.substring(new.length - suffix))
}

private class Segment(val value: String, val end: Int)

private fun readUntilSeparator(raw: String, from: Int, separator: Char): Segment? {
    val out = StringBuilder()
    var i = from
    while (i < raw.length) {
        val c = raw[i]
        when {
            c == '\\' && i + 1 < raw.length && raw[i + 1] == separator -> {
                out.append(separator)
                i += 2
            }
            c == separator -> return Segment(out.toString(), i + 1)
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return null
}

/** Translates sed/Python replacement syntax (`\1`, `\n`) into what Matcher.appendReplacement expects. */
private fun toJavaReplacement(replacement: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < replacement.length) {
        val c = replacement[i]
        if (c == '\\' && i + 1 < replacement.length) {
            val next = replacement[i + 1]
            when {
                next.isDigit() -> out.append('$').append(next)
                next == 'n' -> out.append('\n')
                next == 't' -> out.append('\t')
                else -> out.append('\\').append(next)
            }
            i += 2
        } else {
            if (c == '$' || c == '\\') out.append('\\')
            out.append(c)
            i++
        }
    }
    return out.toString()
}

private fun highestGroupReference(replacement: String): Int {
    var highest = 0
    var i = 0
    while (i < replacement.length) {
        if (replacement[i] == '\\' && i + 1 < replacement.length) {
            val next = replacement[i + 1]
            if (next.isDigit()) highest = maxOf(highest, next - '0')
            i += 2
        } else {
            i++
        }
    }
    return highest
}

private fun escapeHtml(text: String): String {
    val out = StringBuilder(text.length)
    for (c in text) {
        when (c) {
            '&' -> out.append("&amp;")
            '<' -> out.append("&lt;")
            '>' -> out.append("&gt;")
            '"' -> out.append("&quot;")
            '\'' -> out.append("&#39;")
            else -> out.append(c)
        }
    }
    return out.toString()
}

class SedTimeoutException : RuntimeException("sed expression took too long to match")

/** A pathological pattern can backtrack forever; java.util.regex has no timeout, so bail out from charAt. */
private class DeadlinedCharSequence(
        private val delegate: CharSequence,
        private val deadline: Long,
) : CharSequence {
    private var ticks = 0

    override val length: Int get() = delegate.length

    override fun get(index: Int): Char {
        if (++ticks and 0xFFF == 0 && System.currentTimeMillis() > deadline) throw SedTimeoutException()
        return delegate[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            DeadlinedCharSequence(delegate.subSequence(startIndex, endIndex), deadline)

    override fun toString() = delegate.toString()
}
