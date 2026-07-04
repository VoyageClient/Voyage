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
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.util.ContentUtils
import java.util.Calendar

internal data class ParsedSearchQuery(
        /** Lowercased text tokens: bare words and quoted verbatim phrases. All must match. */
        val tokens: List<String>,
        /** from: senders (lowercased); a match needs any of them. */
        val senders: List<String>,
        /** mentions: user ids (lowercased); a match needs all of them. */
        val mentions: List<String>,
        /** has: msgtypes (m.image/m.video/m.audio/m.file/m.sticker); a match needs any of them. */
        val hasTypes: Set<String>,
        /** Inclusive lower bound on origin_server_ts, in ms. */
        val afterTs: Long?,
        /** Exclusive upper bound on origin_server_ts, in ms. */
        val beforeTs: Long?,
) {

    val hasFilters = senders.isNotEmpty() || mentions.isNotEmpty() || hasTypes.isNotEmpty() ||
            afterTs != null || beforeTs != null

    fun matches(
            text: String,
            sender: String?,
            originServerTs: Long,
            msgtype: String?,
            eventMentions: List<String>,
    ): Boolean {
        if (tokens.isEmpty() && !hasFilters) return false
        if (afterTs != null && originServerTs < afterTs) return false
        if (beforeTs != null && originServerTs >= beforeTs) return false
        if (hasTypes.isNotEmpty() && msgtype !in hasTypes) return false
        if (senders.isNotEmpty() && senders.none { it.equals(sender, ignoreCase = true) }) return false
        if (mentions.isNotEmpty()) {
            val lowerMentions = eventMentions.map { it.lowercase() }
            if (!mentions.all { it in lowerMentions }) return false
        }
        if (tokens.isNotEmpty()) {
            // Match only what the user sees: the legacy rich-reply fallback ("> <@user> quoted…")
            // is stripped from display, so it must not make a reply match its quote.
            val lowerText = ContentUtils.extractUsefulTextFromReply(text).lowercase()
            if (!tokens.all { lowerText.contains(it) }) return false
        }
        return true
    }
}

/**
 * Shared query semantics for both search backends: `foo bar` matches messages containing both
 * "foo" and "bar" anywhere; `"foo bar"` matches that exact substring, space included.
 *
 * Unquoted `after:`/`before:` (unix epoch in s or ms, or YYYY-MM-DD), `has:` (image, video,
 * audio, file, sticker), `from:`/`mentions:` (@user:server) words become filters; quoting them
 * keeps them as literal text, and an unrecognised or malformed filter stays literal text too.
 */
internal object SearchQueryParser {

    fun parse(term: String): ParsedSearchQuery {
        val tokens = mutableListOf<String>()
        val senders = mutableListOf<String>()
        val mentions = mutableListOf<String>()
        val hasTypes = mutableSetOf<String>()
        var afterTs: Long? = null
        var beforeTs: Long? = null

        val current = StringBuilder()
        var inQuotes = false

        fun addFilter(word: String): Boolean {
            val colon = word.indexOf(':')
            if (colon <= 0 || colon == word.length - 1) return false
            val value = word.substring(colon + 1)
            when (word.substring(0, colon)) {
                "after" -> afterTs = parseDate(value) ?: return false
                "before" -> beforeTs = parseDate(value) ?: return false
                "has" -> hasTypes.add(HAS_TYPES[value] ?: return false)
                "from" -> senders.add(value)
                "mentions" -> mentions.add(value)
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
                afterTs = afterTs,
                beforeTs = beforeTs,
        )
    }

    /**
     * @return a timestamp in ms from a unix epoch (seconds or ms, told apart by magnitude) or a
     * YYYY-MM-DD date (start of that day, local time), or null if unparseable.
     */
    private fun parseDate(value: String): Long? {
        value.toLongOrNull()?.let { epoch ->
            // Reject implausibly small epochs (e.g. a bare year like "2026" would read as 1970).
            if (epoch < EPOCH_MIN_SECONDS) return null
            return if (epoch < EPOCH_MS_THRESHOLD) epoch * 1000 else epoch
        }
        val parts = value.split('-')
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (year < 1970 || month !in 1..12 || day !in 1..31) return null
        return Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day)
        }.timeInMillis
    }

    // Below this an epoch is seconds (1e11 ms is 1973; 1e11 s is year 5138).
    private const val EPOCH_MS_THRESHOLD = 100_000_000_000L

    // 1e8 s ≈ March 1973; anything below is more likely a typo'd date than a real timestamp.
    private const val EPOCH_MIN_SECONDS = 100_000_000L

    private val HAS_TYPES = mapOf(
            "image" to MessageType.MSGTYPE_IMAGE,
            "video" to MessageType.MSGTYPE_VIDEO,
            "audio" to MessageType.MSGTYPE_AUDIO,
            "file" to MessageType.MSGTYPE_FILE,
            "sticker" to EventType.STICKER,
    )
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
