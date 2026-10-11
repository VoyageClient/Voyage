/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html.highlight

import java.util.regex.Pattern

/**
 * Translates a JavaScript regular expression source into an equivalent [Pattern].
 *
 * Android's ICU-backed engine (and the JVM's) read several constructs differently from JavaScript: shorthand
 * classes and word boundaries are Unicode-aware there but ASCII in JS, `.` excludes a different set of line
 * terminators, `[` inside a class opens a nested class, a `{` that isn't a quantifier is a syntax error, and an
 * escaped letter with no meaning is a literal in JS but an error or a different escape in Java.
 */
internal object JsRegex {

    private const val WORD = "A-Za-z0-9_"
    private const val DIGIT = "0-9"
    private const val SPACE = "\\t\\n\\x0B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private const val DOT = "[^\\n\\r\\u2028\\u2029]"
    private const val WORD_BOUNDARY = "(?:(?<=[$WORD])(?![$WORD])|(?<![$WORD])(?=[$WORD]))"
    private const val NOT_WORD_BOUNDARY = "(?:(?<=[$WORD])(?=[$WORD])|(?<![$WORD])(?![$WORD]))"

    fun compile(source: String, caseInsensitive: Boolean, unicode: Boolean): Pattern {
        val flags = Pattern.MULTILINE or (if (caseInsensitive) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0)
        return Pattern.compile(translate(source, unicode), flags)
    }

    fun translate(source: String, unicode: Boolean): String {
        val groupCount = countCapturingGroups(source)
        val out = StringBuilder(source.length + 16)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when (c) {
                '\\' -> i = translateEscape(source, i, out, groupCount, unicode)
                '[' -> i = translateClass(source, i, out, unicode)
                '.' -> {
                    out.append(DOT)
                    i++
                }
                '{' -> {
                    val end = quantifierEnd(source, i)
                    if (end > 0) {
                        out.append(source, i, end)
                        i = end
                    } else {
                        out.append("\\{")
                        i++
                    }
                }
                '}', ']' -> {
                    out.append('\\').append(c)
                    i++
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * Capture groups inside a negative lookaround. JS always reports these as unset, but Java keeps whatever
     * they captured while the lookaround (unsuccessfully) matched.
     */
    fun negativeLookaroundGroups(source: String): Set<Int> {
        val out = HashSet<Int>()
        val stack = ArrayList<Boolean>()
        var group = 0
        var i = 0
        var inClass = false
        while (i < source.length) {
            val c = source[i]
            when {
                c == '\\' -> i++
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '(' -> {
                    val negative = source.startsWith("(?!", i) || source.startsWith("(?<!", i)
                    val capturing = i + 1 >= source.length || source[i + 1] != '?'
                    if (capturing) {
                        group++
                        if (stack.any { it }) out += group
                    }
                    stack += negative
                }
                c == ')' -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            }
            i++
        }
        return out
    }

    private fun countCapturingGroups(source: String): Int {
        var count = 0
        var i = 0
        var inClass = false
        while (i < source.length) {
            val c = source[i]
            when {
                c == '\\' -> i++
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '(' && (i + 1 >= source.length || source[i + 1] != '?') -> count++
            }
            i++
        }
        return count
    }

    // `{n}`, `{n,}` or `{n,m}` starting at [i]; returns the index after it, or -1.
    private fun quantifierEnd(source: String, i: Int): Int {
        var j = i + 1
        val digitsStart = j
        while (j < source.length && source[j].isAsciiDigit()) j++
        if (j == digitsStart) return -1
        if (j < source.length && source[j] == ',') {
            j++
            while (j < source.length && source[j].isAsciiDigit()) j++
        }
        return if (j < source.length && source[j] == '}') j + 1 else -1
    }

    private fun translateEscape(source: String, i: Int, out: StringBuilder, groupCount: Int, unicode: Boolean): Int {
        if (i + 1 >= source.length) {
            out.append("\\\\")
            return i + 1
        }
        val c = source[i + 1]
        when (c) {
            'd' -> out.append("[$DIGIT]")
            'D' -> out.append("[^$DIGIT]")
            'w' -> out.append("[$WORD]")
            'W' -> out.append("[^$WORD]")
            's' -> out.append("[$SPACE]")
            'S' -> out.append("[^$SPACE]")
            'b' -> out.append(WORD_BOUNDARY)
            'B' -> out.append(NOT_WORD_BOUNDARY)
            'n', 'r', 't', 'f' -> out.append('\\').append(c)
            'v' -> out.append("\\x0B")
            'c' -> {
                if (i + 2 < source.length && source[i + 2].isAsciiLetter()) {
                    out.append("\\c").append(source[i + 2])
                    return i + 3
                }
                out.append("\\\\c")
            }
            'x' -> {
                if (i + 3 < source.length && source[i + 2].isHex() && source[i + 3].isHex()) {
                    out.append(source, i, i + 4)
                    return i + 4
                }
                out.append('x')
            }
            'u' -> return translateUnicodeEscape(source, i, out, unicode)
            'p', 'P' -> {
                if (unicode && i + 2 < source.length && source[i + 2] == '{') {
                    val close = source.indexOf('}', i + 3)
                    out.append('[').append(propertyClass(source.substring(i + 3, close), negated = c == 'P')).append(']')
                    return close + 1
                }
                out.append(c)
            }
            in '0'..'9' -> return translateNumericEscape(source, i, out, groupCount)
            else -> {
                if (c.isLetter()) {
                    // An identity escape: JS reads `\a` as `a`.
                    out.append(c)
                } else {
                    appendLiteral(out, c)
                }
            }
        }
        return i + 2
    }

    private fun translateUnicodeEscape(source: String, i: Int, out: StringBuilder, unicode: Boolean): Int {
        if (unicode && i + 2 < source.length && source[i + 2] == '{') {
            val close = source.indexOf('}', i + 3)
            out.append("\\x{").append(source, i + 3, close).append('}')
            return close + 1
        }
        if ((i + 2..i + 5).all { it < source.length && source[it].isHex() }) {
            out.append(source, i, i + 6)
            return i + 6
        }
        out.append('u')
        return i + 2
    }

    private fun translateNumericEscape(source: String, i: Int, out: StringBuilder, groupCount: Int): Int {
        var j = i + 1
        while (j < source.length && source[j].isAsciiDigit()) j++
        val digits = source.substring(i + 1, j)
        if (digits[0] != '0') {
            val number = digits.toInt()
            if (number <= groupCount) {
                // Delimit it so a literal digit after a short reference isn't read as part of it.
                out.append("(?:\\").append(number).append(')')
                return j
            }
        }
        // Annex B: anything else is a legacy octal escape (up to \377), or the digit itself for 8 and 9.
        var k = i + 1
        var value = 0
        while (k < source.length && k < i + 4 && source[k] in '0'..'7' && value * 8 + (source[k] - '0') <= 255) {
            value = value * 8 + (source[k] - '0')
            k++
        }
        if (k == i + 1) {
            out.append(source[i + 1])
            return i + 2
        }
        appendCodeUnit(out, value)
        return k
    }

    private sealed class ClassItem {
        data class Single(val code: Int) : ClassItem()
        data class Range(val from: Int, val to: Int) : ClassItem()
        data class Set(val members: String, val negated: Boolean) : ClassItem()
    }

    private fun translateClass(source: String, start: Int, out: StringBuilder, unicode: Boolean): Int {
        var i = start + 1
        val negated = i < source.length && source[i] == '^'
        if (negated) i++
        val items = mutableListOf<ClassItem>()
        while (i < source.length && source[i] != ']') {
            val (item, next) = readClassAtom(source, i, unicode)
            i = next
            if (item is ClassItem.Single && i + 1 < source.length && source[i] == '-' && source[i + 1] != ']') {
                val (upper, afterUpper) = readClassAtom(source, i + 1, unicode)
                if (upper is ClassItem.Single) {
                    items += ClassItem.Range(item.code, upper.code)
                    i = afterUpper
                    continue
                }
            }
            items += item
        }
        val end = if (i < source.length) i + 1 else i
        if (items.isEmpty()) {
            out.append(if (negated) "[\\s\\S]" else "(?!)")
            return end
        }
        val negatedSets = items.filterIsInstance<ClassItem.Set>().filter { it.negated }
        if (negatedSets.isEmpty()) {
            out.append('[')
            if (negated) out.append('^')
            items.forEach { appendClassItem(out, it) }
            out.append(']')
            return end
        }
        // Java would need a nested class for e.g. `[\W\d]`; spell the union out as an alternation instead.
        val alternatives = mutableListOf<String>()
        val positive = items.filterNot { it is ClassItem.Set && it.negated }
        if (positive.isNotEmpty()) alternatives += buildString {
            append('[')
            positive.forEach { appendClassItem(this, it) }
            append(']')
        }
        negatedSets.forEach { alternatives += "[^${it.members}]" }
        val union = alternatives.joinToString("|")
        out.append(if (negated) "(?:(?!$union)[\\s\\S])" else "(?:$union)")
        return end
    }

    private fun readClassAtom(source: String, i: Int, unicode: Boolean): Pair<ClassItem, Int> {
        val c = source[i]
        if (c != '\\' || i + 1 >= source.length) return ClassItem.Single(c.code) to i + 1
        val e = source[i + 1]
        return when (e) {
            'd' -> ClassItem.Set(DIGIT, false) to i + 2
            'D' -> ClassItem.Set(DIGIT, true) to i + 2
            'w' -> ClassItem.Set(WORD, false) to i + 2
            'W' -> ClassItem.Set(WORD, true) to i + 2
            's' -> ClassItem.Set(SPACE, false) to i + 2
            'S' -> ClassItem.Set(SPACE, true) to i + 2
            'b' -> ClassItem.Single(0x08) to i + 2
            'n' -> ClassItem.Single('\n'.code) to i + 2
            'r' -> ClassItem.Single('\r'.code) to i + 2
            't' -> ClassItem.Single('\t'.code) to i + 2
            'f' -> ClassItem.Single(0x0C) to i + 2
            'v' -> ClassItem.Single(0x0B) to i + 2
            'c' -> if (i + 2 < source.length && source[i + 2].isAsciiLetter()) {
                ClassItem.Single(source[i + 2].code % 32) to i + 3
            } else {
                ClassItem.Single('\\'.code) to i + 1
            }
            'x' -> if (i + 3 < source.length && source[i + 2].isHex() && source[i + 3].isHex()) {
                ClassItem.Single(source.substring(i + 2, i + 4).toInt(16)) to i + 4
            } else {
                ClassItem.Single('x'.code) to i + 2
            }
            'u' -> when {
                unicode && i + 2 < source.length && source[i + 2] == '{' -> {
                    val close = source.indexOf('}', i + 3)
                    ClassItem.Single(source.substring(i + 3, close).toInt(16)) to close + 1
                }
                (i + 2..i + 5).all { it < source.length && source[it].isHex() } ->
                    ClassItem.Single(source.substring(i + 2, i + 6).toInt(16)) to i + 6
                else -> ClassItem.Single('u'.code) to i + 2
            }
            'p', 'P' -> if (unicode && i + 2 < source.length && source[i + 2] == '{') {
                val close = source.indexOf('}', i + 3)
                ClassItem.Set(propertyClass(source.substring(i + 3, close), negated = false), negated = e == 'P') to close + 1
            } else {
                ClassItem.Single(e.code) to i + 2
            }
            in '0'..'7' -> {
                var k = i + 1
                var value = 0
                while (k < source.length && k < i + 4 && source[k] in '0'..'7' && value * 8 + (source[k] - '0') <= 255) {
                    value = value * 8 + (source[k] - '0')
                    k++
                }
                ClassItem.Single(value) to k
            }
            else -> ClassItem.Single(e.code) to i + 2
        }
    }

    private fun appendClassItem(out: StringBuilder, item: ClassItem) {
        when (item) {
            is ClassItem.Single -> appendCodePoint(out, item.code)
            is ClassItem.Range -> {
                appendCodePoint(out, item.from)
                out.append('-')
                appendCodePoint(out, item.to)
            }
            is ClassItem.Set -> out.append(item.members)
        }
    }

    // Members (without brackets) of a Unicode property class; Java lacks the XID_ properties.
    private fun propertyClass(name: String, negated: Boolean): String {
        val members = when (name) {
            "XID_Start", "ID_Start" -> "\\p{L}\\p{Nl}"
            "XID_Continue", "ID_Continue" -> "\\p{L}\\p{Nl}\\p{Mn}\\p{Mc}\\p{Nd}\\p{Pc}"
            else -> "\\p{$name}"
        }
        return if (negated) "^$members" else members
    }

    private fun appendLiteral(out: StringBuilder, c: Char) {
        if (c.isLetterOrDigit()) out.append(c) else appendCodeUnit(out, c.code)
    }

    private fun appendCodePoint(out: StringBuilder, code: Int) {
        if (code > 0xFFFF) {
            out.append("\\x{").append(Integer.toHexString(code)).append('}')
        } else if (code in 'a'.code..'z'.code || code in 'A'.code..'Z'.code || code in '0'.code..'9'.code) {
            out.append(code.toChar())
        } else {
            appendCodeUnit(out, code)
        }
    }

    private fun appendCodeUnit(out: StringBuilder, code: Int) {
        out.append("\\u").append(Integer.toHexString(code or 0x10000).substring(1))
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
