/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.search

import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageGalleryContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.search.SearchFilters
import org.matrix.android.sdk.api.util.ContentUtils
import org.matrix.android.sdk.api.util.DateArgumentParser

internal data class ParsedSearchQuery(
        /** Lowercased text tokens: bare words and quoted verbatim phrases. All must match. */
        val tokens: List<String>,
        /** from: senders (lowercased); a match needs any of them. */
        val senders: List<String>,
        /** mentions: user ids (lowercased); a match needs all of them. */
        val mentions: List<String>,
        /** has: msgtypes (media, sticker, poll); an event matching any of them is a hit. */
        val hasTypes: Set<String>,
        /** `has:link`: the message must carry a URL. */
        val requiresLink: Boolean,
        /** Inclusive lower bound on origin_server_ts, in ms. */
        val afterTs: Long?,
        /** Exclusive upper bound on origin_server_ts, in ms. */
        val beforeTs: Long?,
) {

    val hasFilters = senders.isNotEmpty() || mentions.isNotEmpty() || hasTypes.isNotEmpty() ||
            requiresLink || afterTs != null || beforeTs != null

    fun matches(
            text: String,
            sender: String?,
            originServerTs: Long,
            msgtypes: Collection<String>,
            eventMentions: List<String>,
    ): Boolean {
        if (tokens.isEmpty() && !hasFilters) return false
        if (afterTs != null && originServerTs < afterTs) return false
        if (beforeTs != null && originServerTs >= beforeTs) return false
        if (hasTypes.isNotEmpty() && hasTypes.none { it in msgtypes }) return false
        if (senders.isNotEmpty() && senders.none { it.equals(sender, ignoreCase = true) }) return false
        if (mentions.isNotEmpty()) {
            val lowerMentions = eventMentions.map { it.lowercase() }
            if (!mentions.all { it in lowerMentions }) return false
        }
        if (tokens.isNotEmpty() || requiresLink) {
            // Match only what the user sees: the legacy rich-reply fallback ("> <@user> quoted…")
            // is stripped from display, so it must not make a reply match its quote.
            val lowerText = ContentUtils.extractUsefulTextFromReply(text).lowercase()
            if (!tokens.all { lowerText.contains(it) }) return false
            if (requiresLink && !LINK_REGEX.containsMatchIn(lowerText)) return false
        }
        return true
    }
}

/**
 * Shared query semantics for both search backends: `foo bar` matches messages containing both
 * "foo" and "bar" anywhere; `"foo bar"` matches that exact substring, space included.
 *
 * Unquoted `after:`/`before:` (any [DateArgumentParser] form), `has:` (image, video,
 * audio, file, sticker, poll, link), `from:`/`mentions:` (@user:server) words become filters; quoting them
 * keeps them as literal text, and an unrecognised or malformed filter stays literal text too.
 */
internal object SearchQueryParser {

    fun parse(term: String): ParsedSearchQuery {
        val tokens = mutableListOf<String>()
        val senders = mutableListOf<String>()
        val mentions = mutableListOf<String>()
        val hasTypes = mutableSetOf<String>()
        var requiresLink = false
        var afterTs: Long? = null
        var beforeTs: Long? = null

        val current = StringBuilder()
        var inQuotes = false

        fun addFilter(word: String): Boolean {
            val colon = word.indexOf(':')
            if (colon <= 0 || colon == word.length - 1) return false
            val value = word.substring(colon + 1)
            when (word.substring(0, colon)) {
                SearchFilters.AFTER -> afterTs = DateArgumentParser.parse(value) ?: return false
                SearchFilters.BEFORE -> beforeTs = DateArgumentParser.parse(value) ?: return false
                SearchFilters.HAS -> if (value == SearchFilters.HAS_LINK) {
                    requiresLink = true
                } else {
                    hasTypes.add(SearchFilters.hasValues[value] ?: return false)
                }
                SearchFilters.FROM -> senders.add(value)
                SearchFilters.MENTIONS -> mentions.add(value)
                else -> return false
            }
            return true
        }

        fun flush(wasQuoted: Boolean) {
            if (current.isNotBlank()) {
                val word = current.toString().lowercase()
                if (wasQuoted || !addFilter(word)) tokens.add(word)
            }
            current.setLength(0)
        }

        for (c in term) {
            when {
                c == '"' -> {
                    flush(wasQuoted = inQuotes)
                    inQuotes = !inQuotes
                }
                !inQuotes && c.isWhitespace() -> flush(wasQuoted = false)
                else -> current.append(c)
            }
        }
        // An unclosed quote simply runs to the end of the term.
        flush(wasQuoted = inQuotes)

        return ParsedSearchQuery(
                tokens = tokens,
                senders = senders,
                mentions = mentions,
                hasTypes = hasTypes,
                requiresLink = requiresLink,
                afterTs = afterTs,
                beforeTs = beforeTs,
        )
    }
}

/**
 * Present an edit (m.replace) event as its target: the timeline only shows the original row, so
 * jumping must target it — and after this remap identical matches dedupe by event id.
 */
internal fun Event.unwrapReplaceForSearch(): Event {
    val relates = content?.get("m.relates_to") as? Map<*, *> ?: return this
    if (relates["rel_type"] != "m.replace") return this
    val target = relates["event_id"] as? String ?: return this
    return copy(eventId = target)
}

// Run against the lowercased body. A bare "example.com" is deliberately not a link: it would match
// any sentence with a dot in it.
private val LINK_REGEX = Regex("""(https?|ftp|matrix|mxc)://|(mailto|geo|tel):|www\.\S+\.\S""")

/**
 * The msgtypes an event matches `has:` on: its own, plus every item of a gallery — one image in a
 * gallery makes the whole gallery a `has:image` hit.
 */
internal fun searchMsgTypes(clearType: String, clearContent: Content?): List<String> = when {
    clearType == EventType.STICKER -> listOf(EventType.STICKER)
    clearType in EventType.POLL_START.values -> listOf(EventType.POLL_START.stable)
    clearType == EventType.MESSAGE -> {
        val msgtype = clearContent?.get(MessageContent.MSG_TYPE_JSON_KEY) as? String
        when {
            msgtype == null -> emptyList()
            MessageType.isGalleryMsgType(msgtype) -> listOf(msgtype) + galleryItemTypes(clearContent)
            else -> listOf(msgtype)
        }
    }
    else -> emptyList()
}

private fun galleryItemTypes(clearContent: Content?): List<String> =
        (clearContent?.get(MessageGalleryContent.ITEMS_JSON_KEY) as? List<*>)
                .orEmpty()
                .mapNotNull { (it as? Map<*, *>)?.get(MessageGalleryContent.ITEM_TYPE_JSON_KEY) as? String }

private val MXID_REGEX = Regex("""@[a-zA-Z0-9._=/+-]+:[a-zA-Z0-9.-]+(?::\d+)?""")

/**
 * User ids an event mentions: the explicit m.mentions list plus any mxid appearing in the
 * formatted or plain body (pills carry the id in their matrix.to href; reply fallbacks quote it).
 */
internal fun extractMentionedUserIds(content: Content?): List<String> {
    content ?: return emptyList()
    val result = LinkedHashSet<String>()
    ((content["m.mentions"] as? Map<*, *>)?.get("user_ids") as? List<*>)
            .orEmpty()
            .forEach { id -> (id as? String)?.let { result.add(it) } }
    listOfNotNull(content["formatted_body"] as? String, content["body"] as? String).forEach { text ->
        MXID_REGEX.findAll(text).forEach { result.add(it.value) }
    }
    return result.toList()
}
