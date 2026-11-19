/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.search.EventAndSender
import org.matrix.android.sdk.api.session.search.SearchResult
import org.matrix.android.sdk.api.util.ContentUtils
import org.matrix.android.sdk.internal.database.mapper.overriddenUserItem
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.session.room.peeking.PeekRoomMessagesTask
import javax.inject.Inject

/**
 * Search for a room the local event index doesn't hold: a peeked (world-readable, un-joined) room, which
 * the server's /search doesn't cover, or an unencrypted room left to server search when the query has no
 * text for the server to search. Crawls /messages backwards and matches locally with the same query
 * semantics as the local event index. The pagination token doubles as the /messages token, so "load more"
 * resumes the crawl where the previous call stopped.
 */
internal class MessagesCrawlSearchTask @Inject constructor(
        private val peekRoomMessagesTask: PeekRoomMessagesTask,
        private val roomAPI: RoomAPI,
        private val globalErrorReceiver: GlobalErrorReceiver,
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
        val memberContents = HashMap<String, RoomMemberContent>()
        val matches = ArrayList<Event>(limit)
        var fromToken = parseToken(nextBatch, searchTerm)
        var seedEventId: String? = null
        if (fromToken == null && query.beforeTs != null) {
            when (val start = startBefore(roomId, query.beforeTs)) {
                Start.NothingBefore -> return@withContext SearchResult(nextBatch = null, highlights = query.tokens, results = emptyList())
                is Start.At -> {
                    start.stateEvents.collectMembers(memberContents)
                    if (matchesQuery(query, start.event)) matches.add(start.event)
                    seedEventId = start.event.eventId
                    fromToken = start.token
                }
                null -> Unit
            }
        }
        var pagesLeft = MAX_PAGES_PER_CALL
        var exhausted = false

        while (matches.size < limit && pagesLeft > 0) {
            pagesLeft--
            val page = peekRoomMessagesTask.execute(PeekRoomMessagesTask.Params(roomId, fromToken, PAGE_SIZE))
            page.stateEvents.collectMembers(memberContents)
            // The resume token is per page, so always scan the page to its end — stopping at the
            // limit would silently skip the tail (the page may return a few extra matches instead).
            page.events.filterTo(matches) { it.eventId != seedEventId && matchesQuery(query, it) }
            fromToken = page.nextToken
            val pastLowerBound = query.afterTs != null && page.events.all { (it.originServerTs ?: 0L) < query.afterTs }
            if (page.events.isEmpty() || page.nextToken == null || pastLowerBound) {
                exhausted = true
                break
            }
        }

        val results = matches.map { event ->
            val senderItem = event.senderId?.let { senderId ->
                val member = memberContents[senderId]
                overriddenUserItem(senderId, member?.displayName, member?.avatarUrl)
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

    private sealed class Start {
        object NothingBefore : Start()
        class At(val event: Event, val token: String, val stateEvents: List<Event>) : Start()
    }

    /**
     * MSC3030: begin the crawl at the newest event before [beforeTs] instead of walking down to it from the
     * live edge. Null when the server can't say, and the crawl starts at the live edge after all.
     */
    private suspend fun startBefore(roomId: String, beforeTs: Long): Start? {
        val hit = try {
            executeRequest(globalErrorReceiver) { roomAPI.getEventForTimestamp(roomId, beforeTs - 1, "b") }
        } catch (e: CancellationException) {
            throw e
        } catch (failure: Throwable) {
            val notFound = failure is Failure.ServerError && failure.httpCode == 404 && failure.error.code == MatrixError.M_NOT_FOUND
            return if (notFound) Start.NothingBefore else null
        }
        return try {
            val context = executeRequest(globalErrorReceiver) {
                roomAPI.getContextOfEvent(roomId, hit.eventId, 0, """{"lazy_load_members":true}""")
            }
            val token = context.start ?: return null
            Start.At(context.event.copy(roomId = context.event.roomId ?: roomId), token, context.stateEvents.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (failure: Throwable) {
            null
        }
    }

    private fun List<Event>.collectMembers(into: MutableMap<String, RoomMemberContent>) = forEach { stateEvent ->
        val userId = stateEvent.stateKey ?: return@forEach
        stateEvent.content.toModel<RoomMemberContent>()?.let { into.putIfAbsent(userId, it) }
    }

    /** Matches what the local index holds for the same event, so both backends answer alike. */
    private fun matchesQuery(query: ParsedSearchQuery, event: Event): Boolean {
        val clearType = event.getClearType()
        if (event.isRedacted()) {
            // As the index keeps it: a row with nothing of its content left to match.
            val kept = clearType in REDACTED_KEPT_TYPES || clearType in EventType.POLL_START.values || clearType in SEARCH_NOTICE_TYPES
            return kept && query.matches("", event.senderId, event.originServerTs ?: 0L, emptyList(), emptyList())
        }
        val clearContent = event.getClearContent()
        val msgtypes = searchMsgTypes(clearType, clearContent)
        if (!isSearchable(clearType, msgtypes)) return false
        val text = searchableText(clearType, clearContent)
        val mentions = if (clearType == EventType.MESSAGE) extractMentionedUserIds(clearContent) else emptyList()
        return query.matches(
                text = ContentUtils.extractUsefulTextFromReply(text.orEmpty()).lowercase(),
                sender = event.senderId,
                originServerTs = event.originServerTs ?: 0L,
                msgtypes = msgtypes,
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
        private val REDACTED_KEPT_TYPES = setOf(EventType.MESSAGE, EventType.STICKER, EventType.ENCRYPTED)

        private const val PAGE_SIZE = 100

        // Bounds how much history a single search/load-more call may crawl.
        private const val MAX_PAGES_PER_CALL = 5
    }
}
