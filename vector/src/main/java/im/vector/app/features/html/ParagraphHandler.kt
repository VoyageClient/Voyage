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
        if (isLoneWrappingParagraph(tag)) return
        val pad = dimensionConverter.dpToPx(4)
        SpannableBuilder.setSpans(
                visitor.builder(),
                VerticalPaddingSpan(pad, pad),
                tag.start(),
                tag.end(),
        )
    }

    // True when this `<p>` is the sole child of HtmlRootTagPlugin's `<div data-root>` —
    // i.e. the wrapping `<p>` is a pure markdown→HTML artifact and shouldn't add padding.
    private fun isLoneWrappingParagraph(tag: HtmlTag): Boolean {
        if (!tag.isBlock) return false
        val parent = tag.asBlock.parent() ?: return false
        val parentIsRoot = parent.isRoot ||
                (parent.name() == HtmlRootTagPlugin.ROOT_TAG_NAME &&
                        parent.attributes().containsKey(HtmlRootTagPlugin.ROOT_ATTRIBUTE))
        if (!parentIsRoot) return false
        return parent.children().size == 1
    }
}
