/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.commonmark.ext.subsupstrike

import org.commonmark.Extension
import org.commonmark.ext.subsupstrike.internal.CaretDelimiterProcessor
import org.commonmark.ext.subsupstrike.internal.PipeDelimiterProcessor
import org.commonmark.ext.subsupstrike.internal.SubSupStrikeHtmlNodeRenderer
import org.commonmark.ext.subsupstrike.internal.TildeDelimiterProcessor
import org.commonmark.node.CustomNode
import org.commonmark.node.Delimited
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Pandoc-style subscript (`~text~` -> `<sub>`) and superscript (`^text^` -> `<sup>`), GFM
 * strikethrough (`~~text~~` -> `<del>`), and Matrix spoilers (`||text||` -> `<span data-mx-spoiler>`).
 * Subscript and strikethrough share the `~` character, so a single delimiter processor distinguishes
 * them by run length. This is deliberately not the GFM strikethrough extension: keeping its
 * `org.commonmark.ext.gfm.strikethrough.Strikethrough` off the classpath lets Markwon's StrikeHandler
 * keep using its plain StrikethroughSpan fallback on the render side.
 */
internal class SubSupStrikeExtension private constructor() : Parser.ParserExtension, HtmlRenderer.HtmlRendererExtension {
    override fun extend(parserBuilder: Parser.Builder) {
        parserBuilder.customDelimiterProcessor(TildeDelimiterProcessor())
        parserBuilder.customDelimiterProcessor(CaretDelimiterProcessor())
        parserBuilder.customDelimiterProcessor(PipeDelimiterProcessor())
    }

    override fun extend(rendererBuilder: HtmlRenderer.Builder) {
        rendererBuilder.nodeRendererFactory { context -> SubSupStrikeHtmlNodeRenderer(context) }
    }

    companion object {
        fun create(): Extension {
            return SubSupStrikeExtension()
        }
    }
}

internal class Subscript : CustomNode(), Delimited {
    override fun getOpeningDelimiter() = "~"
    override fun getClosingDelimiter() = "~"
}

internal class Superscript : CustomNode(), Delimited {
    override fun getOpeningDelimiter() = "^"
    override fun getClosingDelimiter() = "^"
}

internal class Strikethrough : CustomNode(), Delimited {
    override fun getOpeningDelimiter() = "~~"
    override fun getClosingDelimiter() = "~~"
}

internal class Spoiler : CustomNode(), Delimited {
    override fun getOpeningDelimiter() = "||"
    override fun getClosingDelimiter() = "||"
}
