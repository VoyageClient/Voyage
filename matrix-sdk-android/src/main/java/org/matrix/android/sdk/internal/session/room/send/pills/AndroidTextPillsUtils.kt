/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send.pills

import android.text.SpannableString
import org.matrix.android.sdk.api.session.permalinks.PermalinkService
import org.matrix.android.sdk.api.session.room.send.MatrixEmoteSpan
import org.matrix.android.sdk.api.session.room.send.MatrixItemSpan
import org.matrix.android.sdk.api.util.MatrixItem
import java.util.Collections
import javax.inject.Inject

internal class AndroidTextPillsUtils @Inject constructor(
        private val mentionLinkSpecComparator: MentionLinkSpecComparator,
        private val permalinkService: PermalinkService
) : TextPillsUtils {

    override fun processSpecialSpansToHtml(text: CharSequence): String? {
        return transformPills(text, permalinkService.createMentionSpanTemplate(PermalinkService.SpanTemplateType.HTML)) { it.htmlEscape() }
    }

    override fun processSpecialSpansToMarkdown(text: CharSequence): String? {
        return transformPills(text, permalinkService.createMentionSpanTemplate(PermalinkService.SpanTemplateType.MARKDOWN)) { it.markdownEscape() }
    }

    private fun transformPills(text: CharSequence, template: String, escapeLabel: (String) -> String): String? {
        val spannableString = SpannableString.valueOf(text) ?: return null
        val pills = spannableString
                .getSpans(0, text.length, MatrixItemSpan::class.java)
                // we use the raw text for @room notification instead of a link
                .filterNot { it.matrixItem is MatrixItem.EveryoneInRoomItem }
                .map {
                    // The link text is the name the plain body uses, so the two bodies agree.
                    val label = it.bodyText?.takeIf { name -> name.isNotBlank() }
                            ?: it.matrixItem.displayName?.takeIf { name -> name.isNotBlank() }
                            ?: it.matrixItem.id
                    MentionLinkSpec(
                            replacement = String.format(template, it.matrixItem.id, escapeLabel(label)),
                            start = spannableString.getSpanStart(it),
                            end = spannableString.getSpanEnd(it)
                    )
                }
        val emotes = spannableString
                .getSpans(0, text.length, MatrixEmoteSpan::class.java)
                .map {
                    MentionLinkSpec(
                            replacement = it.toEmoticonHtml(),
                            start = spannableString.getSpanStart(it),
                            end = spannableString.getSpanEnd(it)
                    )
                }

        val all = (pills + emotes).toMutableList().takeIf { it.isNotEmpty() } ?: return null

        // we need to prune overlaps!
        pruneOverlaps(all)

        return buildString {
            var currIndex = 0
            all.forEach { (replacement, start, end) ->
                // append text before the span, then its replacement (emote img or mention pill)
                append(text, currIndex, start)
                append(replacement)
                currIndex = end
            }
            // append text after the last span
            append(text, currIndex, text.length)
        }
    }

    private fun MatrixEmoteSpan.toEmoticonHtml(): String {
        val label = ":$shortcode:".htmlEscape()
        return """<img data-mx-emoticon src="${mxcUrl.htmlEscape()}" alt="$label" title="$label" height="32" />"""
    }

    private fun String.htmlEscape(): String = replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    // A display name is free-form, so keep its punctuation from being read as markdown syntax once
    // the label is spliced into a link: brackets would end the label, the rest could style it.
    private fun String.markdownEscape(): String = MARKDOWN_PUNCTUATION.fold(this) { escaped, char ->
        escaped.replace(char.toString(), "\\$char")
    }

    private fun pruneOverlaps(links: MutableList<MentionLinkSpec>) {
        Collections.sort(links, mentionLinkSpecComparator)
        var len = links.size
        var i = 0
        while (i < len - 1) {
            val a = links[i]
            val b = links[i + 1]
            var remove = -1

            // test if there is an overlap
            if (b.start in a.start until a.end) {
                when {
                    b.end <= a.end ->
                        // b is inside a -> b should be removed
                        remove = i + 1
                    a.end - a.start > b.end - b.start ->
                        // overlap and a is bigger -> b should be removed
                        remove = i + 1
                    a.end - a.start < b.end - b.start ->
                        // overlap and a is smaller -> a should be removed
                        remove = i
                }

                if (remove != -1) {
                    links.removeAt(remove)
                    len--
                    continue
                }
            }
            i++
        }
    }

    companion object {
        // Backslash first, so escaping it does not double up the escapes added after it.
        private val MARKDOWN_PUNCTUATION = listOf('\\', '[', ']', '<', '>', '`', '*', '_', '~')
    }
}
