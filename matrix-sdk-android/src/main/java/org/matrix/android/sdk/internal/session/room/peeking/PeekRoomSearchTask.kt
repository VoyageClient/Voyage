/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.peeking

import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.search.EventAndSender
import org.matrix.android.sdk.api.session.search.SearchResult
import org.matrix.android.sdk.api.util.ContentUtils
import org.matrix.android.sdk.api.util.MatrixItem
import org.matrix.android.sdk.internal.session.search.ParsedSearchQuery
import org.matrix.android.sdk.internal.session.search.extractMentionedUserIds
import org.matrix.android.sdk.internal.session.search.unwrapReplaceForSearch
import javax.inject.Inject

/**
 * Search for a peeked (world-readable, un-joined) room: the server's /search only covers rooms the
 * user is a member of, so crawl /messages backwards and match locally with the same query semantics
 * as the local event index. The pagination token doubles as the /messages token, so "load more"
 * resumes the crawl where the previous call stopped.
 */
internal class PeekRoomSearchTask @Inject constructor(
        private val peekRoomMessagesTask: PeekRoomMessagesTask,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
) {

    suspend fun search(
            query: ParsedSearchQuery,
            searchTerm: String,
            roomId: String,
            nextBatch: String?,
            limit: Int
    ): SearchResult = withContext(coroutineDispatchers.computation) {
        if (query.tokens.isEmpty() && !query.hasFilters) {
            return@withContext SearchResult(nextBatch = null, highlights = emptyList(), results = emptyList())
        }
        var fromToken = parseToken(nextBatch, searchTerm)
        val memberContents = HashMap<String, RoomMemberContent>()
        val matches = ArrayList<Event>(limit)
        var pagesLeft = MAX_PAGES_PER_CALL
        var exhausted = false

        while (matches.size < limit && pagesLeft > 0) {
            pagesLeft--
            val page = peekRoomMessagesTask.execute(PeekRoomMessagesTask.Params(roomId, fromToken, PAGE_SIZE))
            page.stateEvents.forEach { stateEvent ->
                val userId = stateEvent.stateKey ?: return@forEach
                stateEvent.content.toModel<RoomMemberContent>()?.let { memberContents.putIfAbsent(userId, it) }
            }
            // The resume token is per page, so always scan the page to its end — stopping at the
            // limit would silently skip the tail (the page may return a few extra matches instead).
            page.events.filterTo(matches) { matchesQuery(query, it) }
            fromToken = page.nextToken
            if (page.events.isEmpty() || page.nextToken == null) {
                exhausted = true
                break
            }
        }

        val results = matches.map { event ->
            val senderItem = event.senderId?.let { senderId ->
                val member = memberContents[senderId]
                MatrixItem.UserItem(senderId, member?.displayName, member?.avatarUrl)
            }
            EventAndSender(event.unwrapReplaceForSearch(), senderItem)
        }
        SearchResult(
                nextBatch = fromToken?.takeUnless { exhausted }?.let { makeToken(it, searchTerm) },
                highlights = query.tokens,
                // The crawl is newest-first; the UI expects the server mapping, which reverses.
                results = results.reversed(),
        )
    }

    private fun matchesQuery(query: ParsedSearchQuery, event: Event): Boolean {
        if (event.isRedacted()) return false
        val clearType = event.getClearType()
        val clearContent = event.getClearContent()
        val msgtype: String
        val text = when (clearType) {
            EventType.MESSAGE -> {
                msgtype = clearContent?.get("msgtype") as? String ?: return false
                if (msgtype.startsWith("m.key.verification")) return false
                clearContent["body"] as? String
            }
            EventType.STICKER -> {
                msgtype = EventType.STICKER
                clearContent?.get("body") as? String
            }
            else -> return false
        }
        val mentions = if (clearType == EventType.MESSAGE) extractMentionedUserIds(clearContent) else emptyList()
        return query.matches(
                text = ContentUtils.extractUsefulTextFromReply(text.orEmpty()).lowercase(),
                sender = event.senderId,
                originServerTs = event.originServerTs ?: 0L,
                msgtype = msgtype,
                eventMentions = mentions.map { it.lowercase() },
        )
    }

    private fun makeToken(messagesToken: String, term: String) = "${term.hashCode()}|$messagesToken"

    /** The UI can replay a token from a previous term; only honour it for the same term. */
    private fun parseToken(token: String?, term: String): String? {
        token ?: return null
        val hash = token.substringBefore('|', missingDelimiterValue = "")
        if (hash != term.hashCode().toString()) return null
        return token.substringAfter('|').takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val PAGE_SIZE = 100

        // Bounds how much history a single search/load-more call may crawl.
        private const val MAX_PAGES_PER_CALL = 5
    }
}
