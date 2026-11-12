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
    data class Parsed(val script: SedScript) : SedParseResult
}

sealed interface SedScriptResult {
    /** [changed] flags each char of [text] that a substitution wrote. */
    class Applied(val text: String, private val changed: BooleanArray) : SedScriptResult {
        /** [text] after [prefix], with each changed run wrapped in `<u>`, escaped for HTML. */
        fun highlightedHtml(prefix: String = ""): String {
            val out = StringBuilder(escapeHtml(prefix))
            var pos = 0
            var start = 0
            while (start < text.length) {
                if (!changed[start]) {
                    start++
                    continue
                }
                var end = start
                while (true) {
                    while (end < text.length && changed[end]) end++
                    var next = end
                    while (next < text.length && !changed[next] && text[next].isWhitespace()) next++
                    if (next > end && next < text.length && changed[next]) end = next else break
                }
                out.append(escapeHtml(text.substring(pos, start)))
                        .append("<u>").append(escapeHtml(text.substring(start, end))).append("</u>")
                pos = end
                start = end
            }
            return out.append(escapeHtml(text.substring(pos))).toString()
        }
    }

    class NoMatch(val step: SedExpression) : SedScriptResult
}

/** Chained expressions run in order on one message, and every one of them must match. */
class SedScript(val steps: List<SedExpression>) {
    val highlight: Boolean get() = steps.all { it.highlight }

    fun apply(body: String, deadline: Long = System.currentTimeMillis() + SED_MATCH_TIMEOUT_MS): SedScriptResult {
        var text = body
        var changed = BooleanArray(body.length)
        for (step in steps) {
            val result = step.apply(text, changed, deadline) ?: return SedScriptResult.NoMatch(step)
            text = result.text
            changed = result.changed
        }
        return SedScriptResult.Applied(text, changed)
    }
}

class SedExpression(
        val source: String,
        private val find: Pattern,
        private val replacement: String,
        private val global: Boolean,
        val highlight: Boolean,
) {
    class Result(val text: String, val changed: BooleanArray)

    private class Edit(val inStart: Int, val inEnd: Int, val outStart: Int, val outEnd: Int)

    /** Returns the substituted text with [changed] carried over and this step's replacements flagged, or null without a match. */
    fun apply(body: String, changed: BooleanArray, deadline: Long): Result? {
        val matcher = find.matcher(DeadlinedCharSequence(body, deadline))
        if (!matcher.find()) return null
        val out = StringBuffer()
        val edits = ArrayList<Edit>()
        var appendPos = 0
        do {
            val outStart = out.length + matcher.start() - appendPos
            matcher.appendReplacement(out, replacement)
            edits.add(Edit(matcher.start(), matcher.end(), outStart, out.length))
            appendPos = matcher.end()
        } while (global && matcher.find())
        matcher.appendTail(out)
        val text = out.toString()

        val outChanged = BooleanArray(text.length)
        val deletions = ArrayList<Int>()
        var inPos = 0
        var outPos = 0
        for (edit in edits) {
            System.arraycopy(changed, inPos, outChanged, outPos, edit.inStart - inPos)
            markEdit(body, text, changed, outChanged, edit)?.let { deletions.add(it) }
            inPos = edit.inEnd
            outPos = edit.outEnd
        }
        System.arraycopy(changed, inPos, outChanged, outPos, body.length - inPos)
        for (position in deletions) {
            // A deletion leaves nothing new to mark, so mark the word it happened in.
            var start = position
            var end = position
            while (start > 0 && !text[start - 1].isWhitespace()) start--
            while (end < text.length && !text[end].isWhitespace()) end++
            outChanged.fill(true, start, end)
        }
        return Result(text, outChanged)
    }

    /**
     * Flags the replacement as one span, narrowed by what it shares with the matched text at either end.
     * Returns the output position of a pure deletion, which has no chars of its own to flag.
     */
    private fun markEdit(body: String, text: String, changed: BooleanArray, outChanged: BooleanArray, edit: Edit): Int? {
        val matchedLength = edit.inEnd - edit.inStart
        val replacedLength = edit.outEnd - edit.outStart
        var prefix = 0
        val maxPrefix = minOf(matchedLength, replacedLength)
        while (prefix < maxPrefix && body[edit.inStart + prefix] == text[edit.outStart + prefix]) prefix++
        var suffix = 0
        val maxSuffix = maxPrefix - prefix
        while (suffix < maxSuffix && body[edit.inEnd - 1 - suffix] == text[edit.outEnd - 1 - suffix]) suffix++
        System.arraycopy(changed, edit.inStart, outChanged, edit.outStart, prefix)
        System.arraycopy(changed, edit.inEnd - suffix, outChanged, edit.outEnd - suffix, suffix)

        var start = edit.outStart + prefix
        var end = edit.outEnd - suffix
        if (start == end) return if (matchedLength != replacedLength) start else null
        var trimmedStart = start
        var trimmedEnd = end
        while (trimmedStart < trimmedEnd && text[trimmedStart].isWhitespace()) trimmedStart++
        while (trimmedEnd > trimmedStart && text[trimmedEnd - 1].isWhitespace()) trimmedEnd--
        if (trimmedStart < trimmedEnd) {
            start = trimmedStart
            end = trimmedEnd
        }
        outChanged.fill(true, start, end)
        return null
    }
}

/**
 * Expressions chain with `;` after a closing delimiter, or one per line. Lines only chain when every
 * one of them is an expression, so a pattern with a literal line break still parses as one.
 */
fun parseSed(text: CharSequence): SedParseResult {
    val raw = text.toString().trim()
    val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.size > 1) {
        val perLine = lines.map { parseChain(it) }
        if (perLine.none { it === SedParseResult.NotSed }) {
            perLine.firstOrNull { it is SedParseResult.Invalid }?.let { return it }
            return SedParseResult.Parsed(SedScript(perLine.flatMap { (it as SedParseResult.Parsed).script.steps }))
        }
    }
    return parseChain(raw)
}

private fun parseChain(raw: String): SedParseResult {
    val steps = ArrayList<SedExpression>()
    var pos = 0
    while (true) {
        when (val step = parseStep(raw, pos)) {
            is StepResult.Failed -> return if (steps.isEmpty() || step.result is SedParseResult.Invalid) {
                step.result
            } else {
                SedParseResult.Invalid("expected a complete expression after ';'")
            }
            is StepResult.Ok -> {
                steps.add(step.expression)
                pos = step.end
            }
        }
        if (pos >= raw.length) break
        pos++
        while (pos < raw.length && raw[pos].isWhitespace()) pos++
        if (pos >= raw.length) break
    }
    return SedParseResult.Parsed(SedScript(steps))
}

private sealed interface StepResult {
    class Failed(val result: SedParseResult) : StepResult

    /** [end] is the index of the `;` that ends this step, or the length of the text. */
    class Ok(val expression: SedExpression, val end: Int) : StepResult
}

private fun parseStep(raw: String, start: Int): StepResult {
    val notSed = StepResult.Failed(SedParseResult.NotSed)
    fun invalid(reason: String) = StepResult.Failed(SedParseResult.Invalid(reason))

    if (raw.length - start < 4 || raw[start] != 's') return notSed
    val separator = raw[start + 1]
    if (separator != '/' && separator != '#') return notSed

    val pattern = readUntilSeparator(raw, start + 2, separator) ?: return notSed
    if (pattern.value.isEmpty()) return invalid("empty search pattern")
    // The trailing delimiter is optional; without it everything left is the replacement and there are no flags.
    val replacement = readUntilSeparator(raw, pattern.end, separator)
    val rawReplacement = replacement?.value ?: raw.substring(pattern.end)

    var flags = Pattern.UNICODE_CASE
    var global = false
    var highlight = true
    var end = raw.length
    var i = replacement?.end ?: raw.length
    while (i < raw.length) {
        val flag = raw[i]
        if (flag == ';') {
            end = i
            break
        }
        if (flag.isWhitespace()) {
            var next = i
            while (next < raw.length && raw[next].isWhitespace()) next++
            if (next < raw.length && raw[next] != ';') return invalid("unknown flag '$flag'")
            i = next
            continue
        }
        when (flag) {
            // Android's regex rejects UNICODE_CHARACTER_CLASS, so \w and friends are ASCII-only
            // regardless — which is what this flag asks for.
            'a' -> Unit
            'i' -> flags = flags or Pattern.CASE_INSENSITIVE
            'm' -> flags = flags or Pattern.MULTILINE
            's' -> flags = flags or Pattern.DOTALL
            'g' -> global = true
            'u' -> highlight = false
            else -> return invalid("unknown flag '$flag'")
        }
        i++
    }

    val compiled = try {
        Pattern.compile(pattern.value, flags)
    } catch (failure: PatternSyntaxException) {
        return invalid(failure.description ?: failure.message.orEmpty())
    }
    val highestGroup = highestGroupReference(rawReplacement)
    if (highestGroup > compiled.matcher("").groupCount()) {
        return invalid("no group \\$highestGroup in the search pattern")
    }
    return StepResult.Ok(
            SedExpression(
                    source = raw.substring(start, end).trim(),
                    find = compiled,
                    replacement = toJavaReplacement(rawReplacement),
                    global = global,
                    highlight = highlight,
            ),
            end,
    )
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
