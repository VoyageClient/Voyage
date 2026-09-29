/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.commonmark.ext.explicitlinks

import org.commonmark.Extension
import org.commonmark.ext.explicitlinks.internal.ExplicitLinksHtmlNodeRenderer
import org.commonmark.renderer.html.HtmlRenderer

/**
 * MSC4550: markdown links the user wrote render as explicit links. Mention pills reach the parser as
 * markdown links too, told apart by [MENTION_TITLE], and render as plain anchors.
 */
internal class ExplicitLinksExtension private constructor() : HtmlRenderer.HtmlRendererExtension {
    override fun extend(rendererBuilder: HtmlRenderer.Builder) {
        rendererBuilder.nodeRendererFactory { context -> ExplicitLinksHtmlNodeRenderer(context) }
    }

    companion object {
        // Invisible, and nothing a user types as a link title.
        const val MENTION_TITLE = "\u2063"

        fun create(): Extension {
            return ExplicitLinksExtension()
        }
    }
}
