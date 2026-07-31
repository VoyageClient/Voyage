/*
 * Copyright 2021 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.util

/**
 * Platform seam for HTML-to-plain-text conversion: Android swaps in an HtmlCompat-based
 * implementation at Matrix init (see [installAndroidHtmlConverter]) to keep its exact historical
 * rendering; the default is a basic strip-tags + unescape-entities fallback for desktop.
 */
internal object HtmlToPlainTextConverter {

    @Volatile
    var converter: (String) -> String = ::basicHtmlToPlainText
}

internal fun String.unescapeHtml(): String = HtmlToPlainTextConverter.converter(this)

private val TAG_REGEX = "<[^>]*>".toRegex()
private val NUMERIC_ENTITY_REGEX = "&#(x[0-9a-fA-F]+|[0-9]+);".toRegex()

private fun basicHtmlToPlainText(html: String): String {
    return html
            .replace("<br\\s*/?>".toRegex(RegexOption.IGNORE_CASE), "\n")
            .replace(TAG_REGEX, "")
            .replace(NUMERIC_ENTITY_REGEX) { match ->
                val value = match.groupValues[1]
                val code = if (value.startsWith("x")) value.drop(1).toIntOrNull(16) else value.toIntOrNull()
                code?.let { String(Character.toChars(it)) } ?: match.value
            }
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
}
