/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.commonmark.ext.underline.internal

import org.commonmark.node.Node
import org.commonmark.node.StrongEmphasis
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlWriter

internal class UnderlineHtmlNodeRenderer(private val context: HtmlNodeRendererContext) : NodeRenderer {

    private val html: HtmlWriter = context.writer

    override fun getNodeTypes(): Set<Class<out Node>> = setOf(StrongEmphasis::class.java)

    override fun render(node: Node) {
        val tag = if ((node as StrongEmphasis).openingDelimiter == "__") "u" else "strong"
        html.tag(tag, context.extendAttributes(node, tag, emptyMap<String, String>()))
        var child = node.firstChild
        while (child != null) {
            val next = child.next
            context.render(child)
            child = next
        }
        html.tag("/$tag")
    }
}
