/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.html

import org.matrix.android.sdk.api.session.room.send.MatrixEmoteSpan

/** The same `<img data-mx-emoticon>` the SDK writes for an emote span on a normal send. */
fun MatrixEmoteSpan.toEmoticonHtml(): String {
    val label = escapeHtmlAttribute(":$shortcode:")
    return "<img data-mx-emoticon src=\"${escapeHtmlAttribute(mxcUrl)}\" alt=\"$label\" title=\"$label\" height=\"32\" />"
}

private fun escapeHtmlAttribute(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
