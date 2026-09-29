/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.send

/**
 * MSC4550: an `<a>` carrying either attribute is a standard link, never displayed as a mention pill.
 * The attribute's value, if any, is ignored.
 */
object ExplicitLinks {
    const val ATTRIBUTE = "data-mx-link"
    const val ATTRIBUTE_UNSTABLE = "data-org.matrix.msc4550.link"

    private val ATTRIBUTE_IN_TAG = Regex(
            """\s(?:data-mx-link|data-org\.matrix\.msc4550\.link)(?=[\s=/>]|$)""",
            RegexOption.IGNORE_CASE
    )

    private val EXPLICIT_ANCHOR = Regex("""<a\s[^>]*>.*?</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun isExplicit(attributes: Map<String, *>): Boolean =
            attributes.containsKey(ATTRIBUTE) || attributes.containsKey(ATTRIBUTE_UNSTABLE)

    /** @param openTag the raw `<a …>` opening tag. */
    fun isExplicitTag(openTag: String): Boolean = ATTRIBUTE_IN_TAG.containsMatchIn(openTag)

    /** [html] with every explicit link, label included, cut out. */
    fun removeExplicitAnchors(html: String): String =
            EXPLICIT_ANCHOR.replace(html) { if (isExplicitTag(it.value.substringBefore('>'))) "" else it.value }
}
