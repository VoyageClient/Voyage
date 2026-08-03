/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.tools

import android.text.SpannableString
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.render.EventTextRenderer
import im.vector.app.features.html.EventHtmlRenderer
import im.vector.app.features.html.PillsPostProcessor
import im.vector.app.features.html.VectorHtmlCompressor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders a room topic for display: markdown (as the composer applies on send) is converted to HTML,
 * literal HTML in the topic is preserved, the combined HTML is rendered like a timeline message
 * (pills for permalinks), then bare matrix ids/aliases are linkified so they are clickable. Reached
 * from non-DI epoxy/view call sites through [formatTopic].
 */
@Singleton
class RoomTopicRenderer @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
        private val htmlRenderer: EventHtmlRenderer,
        private val htmlCompressor: VectorHtmlCompressor,
        private val pillsPostProcessorFactory: PillsPostProcessor.Factory,
        private val textRendererFactory: EventTextRenderer.Factory,
) {
    fun render(topic: CharSequence, roomId: String?, callback: TimelineEventController.UrlClickCallback?): CharSequence {
        val plain = topic.toString()
        // Markdown -> HTML (commonmark passes any literal HTML in the topic through untouched), then the
        // combined HTML is rendered. A null result means the topic is genuinely plain: skip HTML parsing
        // so literal '<' / '&' survive.
        val html = activeSessionHolder.getSafeActiveSession()
                ?.roomService()
                ?.computeFormattedHtml(plain, autoMarkdown = true)
        val base: CharSequence = if (html != null) {
            val pills = pillsPostProcessorFactory.create(roomId)
            val compressed = htmlCompressor.compress(html)
            runCatching {
                (htmlRenderer.render(compressed, pills) as? Spanned)
                        // Markwon strands a leading newline before inline elements (links/emphasis); the plugin
                        // that fixes it only runs when text is set on a TextView, not for this CharSequence path.
                        ?.let { htmlRenderer.removeLeadingNewlineForInlineElement(it) as? Spanned ?: it }
                        ?.trimBlockWhitespace()
            }
                    .getOrNull()
                    ?: SpannableString(plain)
        } else {
            SpannableString(plain)
        }
        return textRendererFactory.create(roomId)
                .render(base)
                .linkify(callback)
                .prepareForDisplay()
    }

    // Wrapping the topic as markdown makes it a block (<p>…</p>), and the block renderer pads the result
    // with leading/trailing newlines a plain topic never had. Discard that edge whitespace so a topic with
    // a link/markdown lays out as compactly as a plain one. Whitespace covered by a block span
    // (blockquote/list margins, line-height) is kept so those blocks still render.
    private fun Spanned.trimBlockWhitespace(): CharSequence {
        fun Char.isTrimable() = this == '\n' || this == ' ' || this == '\t'
        val coveredRanges =
                getSpans(0, length, LeadingMarginSpan::class.java).map { getSpanStart(it) to getSpanEnd(it) } +
                        getSpans(0, length, LineHeightSpan::class.java).map { getSpanStart(it) to getSpanEnd(it) }
        fun covered(at: Int) = coveredRanges.any { (s, e) -> at in s until e }
        var start = 0
        while (start < length && this[start].isTrimable() && !covered(start)) start++
        var end = length
        while (end > start && this[end - 1].isTrimable() && !covered(end - 1)) end--
        return if (start == 0 && end == length) this else subSequence(start, end)
    }
}
