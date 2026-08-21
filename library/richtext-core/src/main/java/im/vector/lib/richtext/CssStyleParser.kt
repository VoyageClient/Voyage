/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.richtext

/** Port of Markwon's `CssInlineStyleParser`, including its bail-out after a malformed declaration. */
internal object CssStyleParser {

    fun parse(inlineStyle: String): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        var index = 0
        val length = inlineStyle.length
        while (true) {
            val builder = StringBuilder()
            var key: String? = null
            var value: String? = null
            var keyHasWhiteSpace = false
            var found: Pair<String, String>? = null
            var i = index
            while (i < length) {
                val c = inlineStyle[i]
                if (key == null) {
                    if (c == ':') {
                        if (builder.isNotEmpty()) key = builder.toString().trim()
                        builder.setLength(0)
                    } else if (c == ';') {
                        builder.setLength(0)
                    } else if (Character.isWhitespace(c)) {
                        if (builder.isNotEmpty()) keyHasWhiteSpace = true
                    } else if (keyHasWhiteSpace) {
                        builder.setLength(0)
                        builder.append(c)
                        keyHasWhiteSpace = false
                    } else {
                        builder.append(c)
                    }
                } else if (value == null) {
                    if (Character.isWhitespace(c)) {
                        if (builder.isNotEmpty()) builder.append(c)
                    } else if (c == ';') {
                        value = builder.toString().trim()
                        builder.setLength(0)
                        if (key.isNotEmpty() && value.isNotEmpty()) {
                            index = i + 1
                            found = key to value
                            break
                        }
                    } else {
                        builder.append(c)
                    }
                }
                i++
            }
            if (found == null && key != null && builder.isNotEmpty()) {
                val v = builder.toString().trim()
                index = length
                if (key.isNotEmpty() && v.isNotEmpty()) found = key to v
            }
            if (found == null) return result
            result.add(found)
        }
    }
}
