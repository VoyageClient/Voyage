/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.SpannableBuilder
import io.noties.markwon.html.HtmlTag
import io.noties.markwon.html.MarkwonHtmlRenderer
import io.noties.markwon.html.TagHandler

/**
 * Span to be added to any <code> found during initial pass.
 * The actual code spans can then be added on a second pass using this
 * span as a reference.
 */
internal class IntermediateCodeSpan(
        var isBlock: Boolean
)

internal class CodeTagHandler : TagHandler() {

    override fun handle(visitor: MarkwonVisitor, renderer: MarkwonHtmlRenderer, tag: HtmlTag) {
        SpannableBuilder.setSpans(
                visitor.builder(), IntermediateCodeSpan(isBlock = false), tag.start(), tag.end()
        )
    }

    override fun supportedTags(): List<String> {
        return listOf("code")
    }
}

/**
 * Pre tag are already handled by HtmlPlugin to keep the formatting.
 * We are only using it to check for <pre><code>*</code></pre> tags.
 */
internal class CodePreTagHandler : TagHandler() {
    override fun handle(visitor: MarkwonVisitor, renderer: MarkwonHtmlRenderer, tag: HtmlTag) {
        val codeSpan = visitor.builder().getSpans(tag.start(), tag.end()).firstOrNull {
            it.what is IntermediateCodeSpan
        }
        if (codeSpan != null) {
            (codeSpan.what as IntermediateCodeSpan).isBlock = true
        }
    }

    override fun supportedTags(): List<String> {
        return listOf("pre")
    }
}

internal class CodePostProcessorTagHandler : TagHandler() {

    override fun supportedTags() = listOf(HtmlRootTagPlugin.ROOT_TAG_NAME)

    override fun handle(visitor: MarkwonVisitor, renderer: MarkwonHtmlRenderer, tag: HtmlTag) {
        if (tag.attributes()[HtmlRootTagPlugin.ROOT_ATTRIBUTE] == null) {
            return
        }

        if (tag.isBlock) {
            visitChildren(visitor, renderer, tag.asBlock)
        }

        // Replace any intermediate code spans with the real formatting spans
        visitor.builder()
                .getSpans(tag.start(), tag.end())
                .filter {
                    it.what is IntermediateCodeSpan
                }.forEach { code ->
                    val intermediateCodeSpan = code.what as IntermediateCodeSpan
                    val theme = visitor.configuration().theme()
                    val span = HtmlCodeSpan(theme, intermediateCodeSpan.isBlock)

                    SpannableBuilder.setSpans(
                            visitor.builder(), span, code.start, code.end
                    )
                }
    }
}
