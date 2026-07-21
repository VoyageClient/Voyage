/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import com.googlecode.htmlcompressor.compressor.Compressor
import com.googlecode.htmlcompressor.compressor.HtmlCompressor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VectorHtmlCompressor @Inject constructor() {

    // All default options are suitable so far
    private val htmlCompressor: Compressor = HtmlCompressor()

    fun compress(html: String): String {
        var result = htmlCompressor.compress(html)

        // Trim space after <br> and <p>, unfortunately the method setRemoveSurroundingSpaces() from the doc does not exist
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
