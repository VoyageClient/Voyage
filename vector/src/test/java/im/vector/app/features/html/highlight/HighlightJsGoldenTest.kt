/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html.highlight

import com.squareup.moshi.JsonReader
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Compares the port against highlight.js itself (golden.json.gz, recorded by tools/highlightjs/golden.js from its
 * per-language test samples): relevance, illegal-lexeme rejection and every scope range.
 */
class HighlightJsGoldenTest {

    private val engine by lazy { HighlightJs { name -> File("src/main/assets/highlightjs/languages", name).inputStream() } }

    private class Case(val code: String, val language: String, val relevance: Double, val illegal: Boolean, val tokens: List<HljsToken>)

    @Suppress("UNCHECKED_CAST")
    private fun cases(): List<Case> {
        val stream = javaClass.classLoader!!.getResourceAsStream("highlightjs/golden.json.gz")!!
        val root = JsonReader.of(GZIPInputStream(stream).source().buffer()).readJsonValue() as Map<String, Any?>
        return (root["cases"] as List<Map<String, Any?>>).map { case ->
            Case(
                    code = case["code"] as String,
                    language = case["language"] as String,
                    relevance = (case["relevance"] as Number).toDouble(),
                    illegal = case["illegal"] as Boolean,
                    tokens = (case["tokens"] as List<List<Any>>).map { HljsToken((it[0] as Number).toInt(), (it[1] as Number).toInt(), it[2] as String) },
            )
        }
    }

    @Test
    fun `highlighting matches highlight js`() {
        val failures = cases().mapNotNull { case ->
            val actual = engine.highlight(case.code, case.language)!!
            when {
                actual.illegal != case.illegal -> "${case.language}: illegal expected ${case.illegal}"
                actual.relevance != case.relevance -> "${case.language}: relevance expected ${case.relevance}, got ${actual.relevance}"
                else -> firstDifference(case.code, case.tokens, actual.tokens)?.let { "${case.language}: $it" }
            }
        }
        assertTrue(failures.joinToString("\n", prefix = "${failures.size} mismatches:\n"), failures.isEmpty())
    }

    @Test
    fun `all grammars are indexed`() {
        assertEquals(192, engine.listLanguages().size)
    }

    private fun firstDifference(code: String, expected: List<HljsToken>, actual: List<HljsToken>): String? {
        for (i in 0 until maxOf(expected.size, actual.size)) {
            val e = expected.getOrNull(i)
            val a = actual.getOrNull(i)
            if (e != a) {
                val at = (e ?: a)!!.start
                return "token $i expected $e got $a near \"${code.substring(maxOf(0, at - 15), minOf(code.length, at + 25)).replace("\n", "\\n")}\""
            }
        }
        return null
    }
}
