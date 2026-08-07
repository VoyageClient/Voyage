/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import android.graphics.Typeface
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import im.vector.app.core.utils.DimensionConverter
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.SpannableBuilder
import io.noties.markwon.html.HtmlTag
import io.noties.markwon.html.MarkwonHtmlRenderer
import io.noties.markwon.html.TagHandler
import me.gujun.android.span.style.VerticalPaddingSpan

/**
 * MSC2184 `<details>` / `<summary>`. Rendered expanded with the summary emphasised and the body
 * indented, rather than collapsed: collapse state would have to live per-view, and timeline views are
 * recycled, so a scrolled-away message would come back showing another message's state.
 */
class DetailsTagHandler(private val dimensionConverter: DimensionConverter) : TagHandler() {

    override fun supportedTags() = listOf("details", "summary")

    override fun handle(visitor: MarkwonVisitor, renderer: MarkwonHtmlRenderer, tag: HtmlTag) {
        if (tag.isBlock) {
            visitChildren(visitor, renderer, tag.asBlock)
        }
        if (tag.start() == tag.end()) return

        when (tag.name()) {
            "summary" -> SpannableBuilder.setSpans(
                    visitor.builder(),
                    StyleSpan(Typeface.BOLD),
                    tag.start(),
                    tag.end(),
            )
            "details" -> {
                val pad = dimensionConverter.dpToPx(4)
                SpannableBuilder.setSpans(
                        visitor.builder(),
                        arrayOf(VerticalPaddingSpan(pad, pad), LeadingMarginSpan.Standard(dimensionConverter.dpToPx(8))),
                        tag.start(),
                        tag.end(),
                )
            }
        }
    }
}
