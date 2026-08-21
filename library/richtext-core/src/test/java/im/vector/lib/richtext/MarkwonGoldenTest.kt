/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.richtext

import com.squareup.moshi.Moshi
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Asserts the shared renderer reproduces the Android Markwon pipeline for every golden (see GOLDEN.md). */
class MarkwonGoldenTest {

    private val goldenDir = File("src/test/resources/markwon-golden")
    private val moshi = Moshi.Builder().build()
    private val adapter = moshi.adapter(Any::class.java)

    private val resolver = TestPillResolver()

    @Test
    fun `compressor matches VectorHtmlCompressor`() {
        val compressor = MatrixHtmlCompressor()
        val failures = ArrayList<String>()
        for ((name, golden) in goldens()) {
            val input = golden["input"] as String
            if (input.contains("<mx-reply", ignoreCase = true)) continue
            val expected = golden["compressed"] as String
            val actual = compressor.compress(input)
            if (expected != actual) failures += "$name\n  expected: ${expected.show()}\n  actual:   ${actual.show()}"
        }
        if (failures.isNotEmpty()) fail("${failures.size} compressor mismatches:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `render matches the markwon stage`() {
        val failures = ArrayList<String>()
        var checked = 0
        for ((name, golden) in goldens()) {
            @Suppress("UNCHECKED_CAST")
            val expected = normalize(golden["markwon"] as Map<String, Any?>)
            val renderer = RichTextRenderer(latexEnabled = true)
            val rendered = RichTextRenderer.trimUncoveredWhitespace(renderer.render(golden["compressed"] as String, PillsPostProcessor(resolver)))
            val actual = normalize(SpanDump.dump(rendered))
            checked++
            if (expected != actual) failures += describeMismatch(name, expected, actual)
        }
        println("checked $checked goldens, ${failures.size} mismatches")
        if (failures.isNotEmpty()) fail("${failures.size}/$checked mismatches:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `display text and segments match the timeline`() {
        val failures = ArrayList<String>()
        var checked = 0
        for ((name, golden) in goldens()) {
            val body = MessageBodyRenderer(resolver, TestMatrixPatterns).render(golden["input"] as String)
            checked++
            val expectedText = normalize(mapOf("text" to golden["text"], "spans" to golden["spans"]))
            val actualText = normalize(SpanDump.dump(body.text))
            if (expectedText != actualText) failures += describeMismatch(name, expectedText, actualText)
            if ((golden["compressed"] as String) != body.compressed) failures += "== $name compressed differs"
            val expectedSegments = golden["segments"]?.let { normalize(it) }
            val actualSegments = body.segments?.let { normalize(dumpSegments(it)) }
            if (expectedSegments != actualSegments) {
                failures += "== $name segments\n  expected: $expectedSegments\n  actual:   $actualSegments"
            }
        }
        println("checked $checked goldens (display), ${failures.size} mismatches")
        if (failures.isNotEmpty()) fail("${failures.size}/$checked display mismatches:\n" + failures.joinToString("\n"))
    }

    private fun dumpSegments(segments: List<RenderedSegment>): List<Map<String, Any?>> = segments.map { segment ->
        when (segment) {
            is RenderedSegment.Text -> linkedMapOf<String, Any?>("kind" to "html", "html" to segment.html) + SpanDump.dump(segment.text)
            is RenderedSegment.Code -> linkedMapOf<String, Any?>("kind" to "code", "code" to segment.code)
            is RenderedSegment.Table -> linkedMapOf<String, Any?>(
                    "kind" to "table",
                    "rows" to segment.rows.map { row ->
                        linkedMapOf<String, Any?>(
                                "header" to row.isHeader,
                                "cells" to row.cells.map { cell ->
                                    linkedMapOf<String, Any?>(
                                            "header" to cell.isHeader,
                                            "align" to cell.alignment.name.lowercase(),
                                            "html" to cell.html,
                                    ) + SpanDump.dump(cell.text)
                                }
                        )
                    }
            )
        }
    }

    private fun goldens(): List<Pair<String, Map<String, Any?>>> =
            goldenDir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }.map { file ->
                @Suppress("UNCHECKED_CAST")
                file.nameWithoutExtension to (adapter.fromJson(file.readText()) as Map<String, Any?>)
            }

    @Suppress("UNCHECKED_CAST")
    private fun describeMismatch(name: String, expected: Map<String, Any?>, actual: Map<String, Any?>): String {
        val sb = StringBuilder("== $name\n")
        val et = expected["text"] as String
        val at = actual["text"] as String
        if (et != at) sb.append("  text expected: ${et.show()}\n  text actual:   ${at.show()}\n")
        val es = expected["spans"] as List<Any?>
        val `as` = actual["spans"] as List<Any?>
        val missing = es.filterNot { it in `as` }
        val extra = `as`.filterNot { it in es }
        missing.forEach { sb.append("  missing: $it\n") }
        extra.forEach { sb.append("  extra:   $it\n") }
        return sb.toString()
    }

    private fun String.show() = "\"" + replace("\n", "\\n").replace("\u00a0", "\\u00a0").replace("\uFFFC", "\\uFFFC") + "\""

    private fun normalize(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to normalize(v) }.toSortedMap()
        is List<*> -> value.map { normalize(it) }
        is Number -> value.toDouble()
        else -> value
    }

    @Suppress("UNCHECKED_CAST")
    private fun normalize(value: Map<String, Any?>): Map<String, Any?> = normalize(value as Any?) as Map<String, Any?>
}
