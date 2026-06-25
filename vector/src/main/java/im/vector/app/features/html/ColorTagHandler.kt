/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.SpannableBuilder
import io.noties.markwon.html.HtmlTag
import io.noties.markwon.html.MarkwonHtmlRenderer
import io.noties.markwon.html.TagHandler

/**
 * Wraps a default Markwon [TagHandler] so text colour (`data-mx-color` / `data-mx-bg-color` /
 * `style="color"`/`"background-color"`) applies on top of the tag's normal formatting. This lets colour
 * work on any element — bold, italic, underline, strikethrough, headings, sup/sub, … — not just `<font>`.
 */
class ColorTagHandler(private val delegate: TagHandler) : TagHandler() {

    override fun supportedTags(): Collection<String> = delegate.supportedTags()

    override fun handle(visitor: MarkwonVisitor, renderer: MarkwonHtmlRenderer, tag: HtmlTag) {
        delegate.handle(visitor, renderer, tag)
        applyColors(visitor, tag)
    }

    companion object {
        fun applyColors(visitor: MarkwonVisitor, tag: HtmlTag) {
            if (tag.isEmpty || tag.start() >= tag.end()) return
            val attributes = tag.attributes()
            HtmlColorParser.foregroundColor(attributes)?.let {
                SpannableBuilder.setSpans(visitor.builder(), ForegroundColorSpan(it), tag.start(), tag.end())
            }
            HtmlColorParser.backgroundColor(attributes)?.let {
                SpannableBuilder.setSpans(visitor.builder(), BackgroundColorSpan(it), tag.start(), tag.end())
            }
        }
    }
}
