/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import im.vector.app.core.di.ActiveSessionHolder
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.RenderProps
import io.noties.markwon.html.HtmlTag
import io.noties.markwon.html.tag.ImageHandler
import io.noties.markwon.html.tag.SimpleTagHandler
import org.matrix.android.sdk.api.MatrixUrls.isMxcUrl

/**
 * Handles `<img>` tags. Inline `mxc://` images that are MSC2545 custom emoticons/stickers are rendered with a
 * self-sizing [EmoteImageSpan] that reserves its size up front, so they render at emoji size and don't
 * reflow/flicker on load. An image counts as an emote when it carries the `data-mx-emoticon` marker, or —
 * for clients that omit the marker — when its width or height is the conventional 32. Every other image is
 * delegated to the default Markwon image handler.
 */
class MxEmoticonTagHandler(
        private val activeSessionHolder: ActiveSessionHolder,
) : SimpleTagHandler() {

    private val defaultImageHandler = ImageHandler.create()

    override fun supportedTags() = listOf("img")

    override fun getSpans(configuration: MarkwonConfiguration, renderProps: RenderProps, tag: HtmlTag): Any? {
        val attributes = tag.attributes()
        val src = attributes["src"]
        val isEmoticon = attributes.containsKey("data-mx-emoticon") ||
                dimensionIs32(attributes["width"]) ||
                dimensionIs32(attributes["height"])
        if (src != null && src.isMxcUrl() && isEmoticon) {
            val resolvedUrl = activeSessionHolder.getSafeActiveSession()?.contentUrlResolver()?.resolveFullSize(src)
            return EmoteImageSpan(
                    shortcode = (attributes["title"] ?: attributes["alt"])?.removeSurrounding(":") ?: "",
                    mxcUrl = src,
                    body = attributes["alt"],
                    resolvedUrl = resolvedUrl,
            )
        }
        return defaultImageHandler.getSpans(configuration, renderProps, tag)
    }

    private fun dimensionIs32(value: String?): Boolean =
            value?.takeWhile { it.isDigit() }?.toIntOrNull() == 32
}
