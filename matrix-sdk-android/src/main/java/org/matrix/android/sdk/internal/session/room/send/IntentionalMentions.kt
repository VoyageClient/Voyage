/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.send

import org.matrix.android.sdk.api.MatrixPatterns
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.permalinks.PermalinkData
import org.matrix.android.sdk.api.session.permalinks.PermalinkParser
import org.matrix.android.sdk.api.session.room.model.message.Mentions
import org.matrix.android.sdk.api.session.room.send.ExplicitLinks

/**
 * Builds the MSC3952 `m.mentions` block of an outgoing message.
 *
 * Once an event carries `m.mentions`, the receiving server stops applying the legacy
 * body-matching push rules to it, so every user pilled in the message has to be listed
 * here or they get no notification at all.
 */
internal object IntentionalMentions {

    private val HREF_REGEX = Regex("""<a\s[^>]*?href\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val ROOM_MENTION_REGEX = Regex("""(?<!\w)@room(?!\w)""")
    private val BLOCKQUOTE_REGEX = Regex("""<blockquote\b.*?</blockquote>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val QUOTED_LINE_REGEX = Regex("""(?m)^\s*>.*$""")

    /**
     * @param body the plain text body, scanned for an `@room` notification.
     * @param formattedBody the HTML body, scanned for mention pills.
     * @param extraUserIds users to mention regardless of the body, e.g. the sender of a replied-to event.
     * @param selfUserId the current user, never mentioned by their own message.
     * @param literalMentions ranges of [body] whose mentions are meant literally: an `@room` there doesn't count.
     */
    fun build(
            body: CharSequence?,
            formattedBody: String?,
            extraUserIds: List<String> = emptyList(),
            selfUserId: String? = null,
            literalMentions: List<IntRange> = emptyList(),
    ): Mentions? {
        val userIds = LinkedHashSet(extraUserIds)
        // Quoted content is someone else's text: pilling a user there is not mentioning them.
        val unquotedHtml = formattedBody?.replace(BLOCKQUOTE_REGEX, "")
        unquotedHtml?.let { html ->
            HREF_REGEX.findAll(html).forEach { match ->
                val tagEnd = html.indexOf('>', match.range.last).takeIf { it >= 0 } ?: html.length
                if (ExplicitLinks.isExplicitTag(html.substring(match.range.first, tagEnd))) return@forEach
                userIdOf(match.groupValues[1].unescapeHtmlEntities())?.let { userIds.add(it) }
            }
        }
        selfUserId?.let { userIds.remove(it) }
        // The plain body is markdown source, so an explicit link's label can only be told apart in the HTML.
        val htmlOutsideExplicitLinks = unquotedHtml?.let { ExplicitLinks.removeExplicitAnchors(it) }?.takeIf { it != unquotedHtml }
        val room = body != null && mentionsRoom(body, literalMentions) &&
                (htmlOutsideExplicitLinks == null || ROOM_MENTION_REGEX.containsMatchIn(htmlOutsideExplicitLinks))
        // An empty block, so the server's legacy body-matching rules don't notify for the literal text either.
        if (userIds.isEmpty() && !room) return Mentions().takeIf { literalMentions.isNotEmpty() }
        return Mentions(
                room = true.takeIf { room },
                userIds = userIds.toList().takeIf { it.isNotEmpty() },
        )
    }

    /**
     * [content] without `m.mentions.room`, in an edit's `m.new_content` too. The emptied `m.mentions`
     * stays, so the server's legacy body-matching `@room` rule doesn't fire either.
     */
    fun withoutRoomMention(content: Content): Content {
        val newContent = (content[NEW_CONTENT_KEY] as? Map<*, *>)?.let { inner ->
            @Suppress("UNCHECKED_CAST")
            withoutRoomMention(inner as Content).takeIf { it !== inner }
        }
        val mentions = content[MENTIONS_KEY] as? Map<*, *>
        val strippedMentions = mentions?.takeIf { it.containsKey("room") }?.filterKeys { it != "room" }
        if (newContent == null && strippedMentions == null) return content
        return content.toMutableMap().apply {
            newContent?.let { put(NEW_CONTENT_KEY, it) }
            strippedMentions?.let { put(MENTIONS_KEY, it) }
        }
    }

    private const val MENTIONS_KEY = "m.mentions"
    private const val NEW_CONTENT_KEY = "m.new_content"

    private fun mentionsRoom(body: CharSequence, literal: List<IntRange>): Boolean {
        val text = body.toString()
        // Quoted content is someone else's text.
        val quoted = QUOTED_LINE_REGEX.findAll(text).map { it.range }.toList()
        return ROOM_MENTION_REGEX.findAll(text).any { match ->
            val at = match.range.first
            quoted.none { at in it } && literal.none { at in it }
        }
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
