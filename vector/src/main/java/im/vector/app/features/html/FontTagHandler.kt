/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */
package im.vector.app.features.html

import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.RenderProps
import io.noties.markwon.html.HtmlTag
import io.noties.markwon.html.tag.SimpleTagHandler

/**
 * custom to matrix for IRC-style font coloring.
 */
class FontTagHandler : SimpleTagHandler() {

    override fun supportedTags() = listOf("font")

    override fun getSpans(configuration: MarkwonConfiguration, renderProps: RenderProps, tag: HtmlTag): Any? {
        val attributes = tag.attributes()
        val spans = ArrayList<Any>(2)
        HtmlColorParser.foregroundColor(attributes)?.let { spans.add(ForegroundColorSpan(it)) }
        HtmlColorParser.backgroundColor(attributes)?.let { spans.add(BackgroundColorSpan(it)) }
        return spans.takeIf { it.isNotEmpty() }?.toTypedArray()
    }
}
