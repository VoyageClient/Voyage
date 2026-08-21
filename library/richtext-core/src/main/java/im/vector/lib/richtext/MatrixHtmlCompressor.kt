/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.richtext

import com.googlecode.htmlcompressor.compressor.HtmlCompressor

class MatrixHtmlCompressor {

    private val htmlCompressor = HtmlCompressor()

    fun compress(html: String): String {
        var result = htmlCompressor.compress(html)
        result = result.replace("<br> ", "<br>")
        result = result.replace("<br/> ", "<br/>")
        result = result.replace("<br /> ", "<br />")
        result = result.replace("<p> ", "<p>")
        // A space next to an explicit line break never renders; stripping it keeps a <br> between
        // blocks producing a clean empty line instead of a space-only one.
        result = result.replace(" <br>", "<br>")
        result = result.replace(" <br/>", "<br/>")
        result = result.replace(" <br />", "<br />")
        return result
    }
}
