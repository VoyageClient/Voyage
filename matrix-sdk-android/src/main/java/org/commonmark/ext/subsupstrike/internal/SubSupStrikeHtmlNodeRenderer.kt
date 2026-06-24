/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.commonmark.ext.subsupstrike.internal

import org.commonmark.ext.subsupstrike.Strikethrough
import org.commonmark.ext.subsupstrike.Subscript
import org.commonmark.ext.subsupstrike.Superscript
import org.commonmark.node.Node
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlWriter

internal class SubSupStrikeHtmlNodeRenderer(private val context: HtmlNodeRendererContext) : NodeRenderer {

    private val html: HtmlWriter = context.writer

    override fun getNodeTypes(): Set<Class<out Node>> = setOf(
            Subscript::class.java,
            Superscript::class.java,
            Strikethrough::class.java
    )

    override fun render(node: Node) {
        val tag = when (node) {
            is Subscript -> "sub"
            is Superscript -> "sup"
            is Strikethrough -> "del"
            else -> return
        }
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
