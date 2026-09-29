/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.commonmark.ext.explicitlinks.internal

import org.commonmark.ext.explicitlinks.ExplicitLinksExtension
import org.commonmark.internal.util.Escaping
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlWriter
import org.matrix.android.sdk.api.session.room.send.ExplicitLinks

internal class ExplicitLinksHtmlNodeRenderer(private val context: HtmlNodeRendererContext) : NodeRenderer {

    private val html: HtmlWriter = context.writer

    override fun getNodeTypes(): Set<Class<out Node>> = setOf(Link::class.java)

    override fun render(node: Node) {
        val link = node as Link
        val isMention = link.title == ExplicitLinksExtension.MENTION_TITLE
        val attributes = LinkedHashMap<String, String>()
        attributes["href"] = context.encodeUrl(link.destination)
        if (!isMention && link.title != null) attributes["title"] = link.title
        // HtmlWriter always writes name="value"; the MSC4550 markers go out valueless.
        val openTag = buildString {
            append("<a")
            context.extendAttributes(link, "a", attributes).forEach { (name, value) ->
                append(' ').append(name).append("=\"").append(Escaping.escapeHtml(value)).append('"')
            }
            if (!isMention) append(' ').append(ExplicitLinks.ATTRIBUTE).append(' ').append(ExplicitLinks.ATTRIBUTE_UNSTABLE)
            append('>')
        }
        html.raw(openTag)
        var child = link.firstChild
        while (child != null) {
            val next = child.next
            context.render(child)
            child = next
        }
        html.tag("/a")
    }
}
