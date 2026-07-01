/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.commonmark.ext.subsupstrike.internal

import org.commonmark.ext.subsupstrike.Spoiler
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
            Strikethrough::class.java,
            Spoiler::class.java
    )

    override fun render(node: Node) {
        val (tag, attributes) = when (node) {
            is Subscript -> "sub" to emptyMap<String, String>()
            is Superscript -> "sup" to emptyMap()
            is Strikethrough -> "del" to emptyMap()
            is Spoiler -> "span" to mapOf("data-mx-spoiler" to "")
            else -> return
        }
        html.tag(tag, context.extendAttributes(node, tag, attributes))
        var child = node.firstChild
        while (child != null) {
            val next = child.next
            context.render(child)
            child = next
        }
        html.tag("/$tag")
    }
}
