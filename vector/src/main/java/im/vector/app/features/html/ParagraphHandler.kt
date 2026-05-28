/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import im.vector.app.core.utils.DimensionConverter
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.SpannableBuilder
import io.noties.markwon.html.HtmlTag
import io.noties.markwon.html.MarkwonHtmlRenderer
import io.noties.markwon.html.TagHandler
import me.gujun.android.span.style.VerticalPaddingSpan

class ParagraphHandler(private val dimensionConverter: DimensionConverter) : TagHandler() {

    override fun supportedTags() = listOf("p")

    override fun handle(visitor: MarkwonVisitor, renderer: MarkwonHtmlRenderer, tag: HtmlTag) {
        if (tag.isBlock) {
            visitChildren(visitor, renderer, tag.asBlock)
        }
        // <p> in formatted_body is overwhelmingly just a markdown→HTML paragraph wrap, even
        // for single-line messages. Adding 4dp top + 4dp bottom to every paragraph leaves
        // visible empty bands above and below the text inside the bubble. Pad only between
        // sibling paragraphs: top padding when there's already non-whitespace content above
        // this <p> in the builder, bottom padding when there's non-whitespace content after.
        // First/last paragraph in the message gets the matching side zeroed out so the
        // bubble hugs the text on its outer edges.
        val builder = visitor.builder()
        val start = tag.start()
        val end = tag.end().coerceAtMost(builder.length)
        if (start >= end) return
        val pad = dimensionConverter.dpToPx(4)
        val topPad = if ((0 until start).any { !builder[it].isWhitespace() }) pad else 0
        val bottomPad = if ((end until builder.length).any { !builder[it].isWhitespace() }) pad else 0
        if (topPad == 0 && bottomPad == 0) return
        SpannableBuilder.setSpans(
                builder,
                VerticalPaddingSpan(topPad, bottomPad),
                start,
                end,
        )
    }
}
