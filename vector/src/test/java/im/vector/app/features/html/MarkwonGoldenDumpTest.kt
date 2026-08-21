/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.widget.TextView
import com.squareup.moshi.Moshi
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.ColorProvider
import im.vector.app.features.home.AvatarRenderer
import im.vector.app.features.home.room.detail.timeline.render.EventTextRenderer
import im.vector.app.features.home.room.detail.timeline.render.ProcessBodyOfReplyToEventUseCase
import im.vector.app.features.home.room.detail.timeline.tools.linkify
import im.vector.app.features.settings.VectorPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.user.model.User
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Renders every `richtext-corpus` HTML file through the real timeline pipeline and writes one JSON
 * golden per case (see library/richtext-core/GOLDEN.md). Regenerate with
 * `./gradlew :vector:testDebugUnitTest --tests '*MarkwonGoldenDumpTest*'`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MarkwonGoldenDumpTest {

    private val roomId = "!roomid:example.org"

    private val context = RuntimeEnvironment.getApplication().apply {
        setTheme(im.vector.lib.ui.styles.R.style.Theme_Vector_Light)
    }

    private val session = mockk<Session>(relaxed = true).also { session ->
        every { session.contentUrlResolver().resolveFullSize(any()) } answers {
            firstArg<String?>()?.let { "https://media.example.org/_matrix/media/v3/download/" + it.removePrefix("mxc://") }
        }
        // Same rule as DefaultPermalinkService.isPermalinkSupported.
        every { session.permalinkService().isPermalinkSupported(any(), any()) } answers {
            val hosts = firstArg<Array<String>>()
            val url = secondArg<String>()
            url.startsWith("https://matrix.to/#/") ||
                    url.startsWith("matrix:", ignoreCase = true) ||
                    hosts.any { android.net.Uri.parse(url).host == it }
        }
        every { session.userService().getUser(any()) } answers {
            val id = firstArg<String>()
            User(id, displayName = if (id == "@alice:example.org") "Alice" else null)
        }
        every { session.roomService().getRoomSummary(any()) } returns null
        every { session.roomService().getRoomMember(any(), any()) } returns null
    }

    private val sessionHolder = mockk<ActiveSessionHolder>(relaxed = true).also {
        every { it.getActiveSession() } returns session
        every { it.getSafeActiveSession() } returns session
    }

    private val vectorPreferences = mockk<VectorPreferences>(relaxed = true).also {
        every { it.latexMathsIsEnabled() } returns true
    }

    private val renderer = EventHtmlRenderer(
            MatrixHtmlPluginConfigure(ColorProvider(context), context.resources, sessionHolder),
            context,
            sessionHolder,
            vectorPreferences,
    )
    private val compressor = VectorHtmlCompressor()
    private val avatarRenderer = mockk<AvatarRenderer>(relaxed = true)
    private val pillsPostProcessor = PillsPostProcessor(roomId, context, avatarRenderer, sessionHolder)
    private val textRenderer = EventTextRenderer(roomId, context, avatarRenderer, sessionHolder, mockk(relaxed = true))
    private val replyStripper = ProcessBodyOfReplyToEventUseCase(sessionHolder, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

    private val moshi = Moshi.Builder().build()
    private val jsonAdapter = moshi.adapter(Any::class.java).indent("  ")

    @Test
    fun `dump markwon goldens for the corpus`() {
        SpanDump.density = context.resources.displayMetrics.density
        val corpusDir = File("src/test/resources/richtext-corpus")
        assertTrue("corpus dir missing: ${corpusDir.absolutePath}", corpusDir.isDirectory)
        val outDir = File(
                System.getProperty("RICHTEXT_GOLDEN_DIR")
                        ?: System.getenv("RICHTEXT_GOLDEN_DIR")
                        ?: "../library/richtext-core/src/test/resources/markwon-golden"
        )
        outDir.mkdirs()
        val cases = corpusDir.listFiles { f -> f.extension == "html" }!!.sortedBy { it.name }
        assertTrue(cases.isNotEmpty())
        var failures = 0
        for (case in cases) {
            val input = case.readText()
            val golden = LinkedHashMap<String, Any?>()
            golden["input"] = input
            try {
                golden.putAll(renderCase(input))
            } catch (t: Throwable) {
                failures++
                golden["error"] = t.toString()
                golden["stack"] = t.stackTrace.take(8).map { it.toString() }
            }
            File(outDir, case.nameWithoutExtension + ".json").writeText(jsonAdapter.toJson(golden) + "\n")
        }
        println("Wrote ${cases.size} goldens to ${outDir.absolutePath} ($failures with errors)")
    }

    // Mirrors MessageItemFactory.buildFormattedTextItem + buildMessageTextItem for the rendering stages.
    private fun renderCase(input: String): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val bare = replyStripper.stripExistingMxReply(input)
        val compressed = compressor.compress(bare)
        out["compressed"] = compressed

        val rendered = (renderer.render(compressed, pillsPostProcessor) as Spanned).trimUncoveredWhitespace()
        // What MessageTextItem binds: EventTextRenderer (plain permalinks / @room pills) → linkify → the
        // TextView plugin pass (Markwon #423 leading-newline fix, intermediate code span cleanup, emote binding).
        val displayed = bindToTextView(textRenderer.render(rendered).linkify(null))
        out.putAll(SpanDump.dump(displayed))
        out["markwon"] = SpanDump.dump(rendered)

        if (compressed.contains("<table", ignoreCase = true) || compressed.contains("<pre", ignoreCase = true)) {
            val segments = HtmlBodySegmenter.segment(compressed)
            if (segments.any { it !is BodySegment.Html }) {
                out["segments"] = segments.map { segment ->
                    when (segment) {
                        is BodySegment.Html -> linkedMapOf<String, Any?>("kind" to "html", "html" to segment.html) + renderFragment(segment.html)
                        is BodySegment.Code -> linkedMapOf<String, Any?>("kind" to "code", "code" to segment.code)
                        is BodySegment.Table -> linkedMapOf<String, Any?>(
                                "kind" to "table",
                                "rows" to segment.rows.map { row ->
                                    linkedMapOf<String, Any?>(
                                            "header" to row.isHeader,
                                            "cells" to row.cells.map { cell ->
                                                val cellHtml = cell.html.trim()
                                                linkedMapOf<String, Any?>(
                                                        "header" to cell.isHeader,
                                                        "align" to cell.alignment.name.lowercase(),
                                                        "html" to cell.html,
                                                ) + (if (cellHtml.isEmpty()) mapOf("text" to "", "spans" to emptyList<Any>()) else renderFragment(cellHtml))
                                            }
                                    )
                                }
                        )
                    }
                }
            }
        }
        return out
    }

    // RichMessageBodyRenderer.buildTextView / buildCellView: render(html, pills).linkify(...)
    private fun renderFragment(html: String): Map<String, Any?> {
        val spanned = bindToTextView(renderer.render(html, pillsPostProcessor).linkify(null))
        return SpanDump.dump(spanned)
    }

    private fun bindToTextView(text: CharSequence): Spanned {
        val textView = TextView(context)
        renderer.setTextWithPlugins(textView, text)
        return textView.text as Spanned
    }

    // Copy of MessageItemFactory.trimUncoveredWhitespace (private there).
    private fun Spanned.trimUncoveredWhitespace(): Spanned {
        fun Char.isTrimable() = this == '\n' || this == ' ' || this == '\t'
        val coveredRanges =
                getSpans(0, length, LeadingMarginSpan::class.java).map { getSpanStart(it) to getSpanEnd(it) } +
                        getSpans(0, length, LineHeightSpan::class.java).map { getSpanStart(it) to getSpanEnd(it) }
        fun covered(at: Int) = coveredRanges.any { (s, e) -> at in s until e }
        var start = 0
        while (start < length && this[start].isTrimable() && !covered(start)) start++
        var end = length
        while (end > start && this[end - 1].isTrimable() && !covered(end - 1)) end--
        return if (start == 0 && end == length) this else subSequence(start, end) as Spanned
    }
}
