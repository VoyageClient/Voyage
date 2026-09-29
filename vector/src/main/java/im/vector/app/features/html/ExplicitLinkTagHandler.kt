/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import android.text.Spanned
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.SpannableBuilder
import io.noties.markwon.html.HtmlTag
import io.noties.markwon.html.MarkwonHtmlRenderer
import io.noties.markwon.html.tag.LinkHandler
import org.matrix.android.sdk.api.session.room.send.ExplicitLinks

/** Marks the range of an MSC4550 explicit link, which must never be turned into a pill. */
class ExplicitLinkSpan

class ExplicitLinkTagHandler : LinkHandler() {

    override fun handle(visitor: MarkwonVisitor, renderer: MarkwonHtmlRenderer, tag: HtmlTag) {
        super.handle(visitor, renderer, tag)
        if (tag.start() < tag.end() && ExplicitLinks.isExplicit(tag.attributes())) {
            SpannableBuilder.setSpans(visitor.builder(), ExplicitLinkSpan(), tag.start(), tag.end())
        }
    }
}

fun Spanned.overlapsExplicitLink(start: Int, end: Int): Boolean =
        getSpans(start, end, ExplicitLinkSpan::class.java).any { getSpanStart(it) < end && start < getSpanEnd(it) }
