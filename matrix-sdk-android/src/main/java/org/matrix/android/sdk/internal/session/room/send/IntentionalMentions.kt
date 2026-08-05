/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import org.matrix.android.sdk.api.MatrixPatterns
import org.matrix.android.sdk.api.session.permalinks.PermalinkData
import org.matrix.android.sdk.api.session.permalinks.PermalinkParser
import org.matrix.android.sdk.api.session.room.model.message.Mentions

/**
 * Builds the MSC3952 `m.mentions` block of an outgoing message.
 *
 * Once an event carries `m.mentions`, the receiving server stops applying the legacy
 * body-matching push rules to it, so every user pilled in the message has to be listed
 * here or they get no notification at all.
 */
internal object IntentionalMentions {

    private val HREF_REGEX = Regex("""<a\s[^>]*?href\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val ROOM_MENTION_REGEX = Regex("""(^|\W)@room(\W|$)""")
    private val BLOCKQUOTE_REGEX = Regex("""<blockquote\b.*?</blockquote>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val QUOTED_LINE_REGEX = Regex("""(?m)^\s*>.*$""")

    /**
     * @param body the plain text body, scanned for an `@room` notification.
     * @param formattedBody the HTML body, scanned for mention pills.
     * @param extraUserIds users to mention regardless of the body, e.g. the sender of a replied-to event.
     * @param selfUserId the current user, never mentioned by their own message.
     */
    fun build(
            body: String?,
            formattedBody: String?,
            extraUserIds: List<String> = emptyList(),
            selfUserId: String? = null,
    ): Mentions? {
        val userIds = LinkedHashSet(extraUserIds)
        // Quoted content is someone else's text: pilling a user there is not mentioning them.
        formattedBody?.replace(BLOCKQUOTE_REGEX, "")?.let { html ->
            HREF_REGEX.findAll(html).forEach { match ->
                userIdOf(match.groupValues[1].unescapeHtmlEntities())?.let { userIds.add(it) }
            }
        }
        selfUserId?.let { userIds.remove(it) }
        val room = body?.replace(QUOTED_LINE_REGEX, "")?.let { ROOM_MENTION_REGEX.containsMatchIn(it) } == true
        if (userIds.isEmpty() && !room) return null
        return Mentions(
                room = true.takeIf { room },
                userIds = userIds.toList().takeIf { it.isNotEmpty() },
        )
    }

    private fun userIdOf(href: String): String? {
        (PermalinkParser.parse(href) as? PermalinkData.UserLink)?.let { return it.userId }
        // matrix: URIs (MSC2312) are not handled by PermalinkParser.
        return href.removePrefix("matrix:u/")
                .takeIf { it != href }
                ?.substringBefore('?')
                ?.let { "@$it" }
                ?.takeIf { MatrixPatterns.isUserId(it) }
    }

    private fun String.unescapeHtmlEntities(): String = replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
}
