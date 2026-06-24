/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.commonmark.ext.underline

import org.commonmark.Extension
import org.commonmark.ext.underline.internal.UnderlineHtmlNodeRenderer
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Discord-style underline: `__text__` renders as underline instead of bold, while `**text**`
 * stays bold. This reuses commonmark's built-in underscore parsing (which can't be overridden via
 * a custom delimiter processor) and only remaps the rendering based on the delimiter that was used.
 */
internal class UnderlineExtension private constructor() : HtmlRenderer.HtmlRendererExtension {
    override fun extend(rendererBuilder: HtmlRenderer.Builder) {
        rendererBuilder.nodeRendererFactory { context -> UnderlineHtmlNodeRenderer(context) }
    }

    companion object {
        fun create(): Extension {
            return UnderlineExtension()
        }
    }
}
