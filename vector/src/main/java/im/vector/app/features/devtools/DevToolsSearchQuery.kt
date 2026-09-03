/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.devtools

class DevToolsSearchQuery private constructor(private val terms: List<Term>) {

    val isEmpty: Boolean get() = terms.isEmpty()

    fun matches(type: String?, stateKey: String?, content: Map<String, Any?>?): Boolean {
        return terms.all { it.matches(type, stateKey, content) }
    }

    private enum class Field { ANY, TYPE, KEY, CONTENT }

    private class Term(private val field: Field, private val needle: String) {

        fun matches(type: String?, stateKey: String?, content: Map<String, Any?>?): Boolean {
            return when (field) {
                Field.TYPE -> type.containsNeedle()
                Field.KEY -> stateKey.containsNeedle()
                Field.CONTENT -> contentContains(content)
                Field.ANY -> type.containsNeedle() || stateKey.containsNeedle() || contentContains(content)
            }
        }

        private fun String?.containsNeedle() = this != null && contains(needle, ignoreCase = true)

        private fun contentContains(value: Any?): Boolean = when (value) {
            null -> false
            is Map<*, *> -> value.any { (key, entry) -> key.toString().containsNeedle() || contentContains(entry) }
            is Collection<*> -> value.any { contentContains(it) }
            is Array<*> -> value.any { contentContains(it) }
            else -> value.toString().containsNeedle()
        }
    }

    companion object {

        fun parse(raw: String): DevToolsSearchQuery {
            return DevToolsSearchQuery(tokenize(raw).mapNotNull { toTerm(it) })
        }

        private fun toTerm(token: String): Term? {
            val field = when {
                token.startsWith("type:", ignoreCase = true) -> Field.TYPE
                token.startsWith("key:", ignoreCase = true) -> Field.KEY
                token.startsWith("content:", ignoreCase = true) -> Field.CONTENT
                else -> Field.ANY
            }
            val needle = if (field == Field.ANY) token else token.substringAfter(':')
            return needle.takeIf { it.isNotEmpty() }?.let { Term(field, it) }
        }

        /** Whitespace separated, with double quotes protecting spaces (`key:"@a b:c"`). */
        private fun tokenize(raw: String): List<String> {
            val tokens = mutableListOf<String>()
            val current = StringBuilder()
            var quoted = false
            raw.forEach { char ->
                when {
                    char == '"' -> quoted = !quoted
                    char.isWhitespace() && !quoted -> {
                        if (current.isNotEmpty()) tokens.add(current.toString())
                        current.setLength(0)
                    }
                    else -> current.append(char)
                }
            }
            if (current.isNotEmpty()) tokens.add(current.toString())
            return tokens
        }
    }
}
