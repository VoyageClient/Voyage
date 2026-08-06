/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.search.index

import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.search.EventAndSender
import org.matrix.android.sdk.api.session.search.SearchResult
import org.matrix.android.sdk.api.util.MatrixItem
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.search.ParsedSearchQuery
import org.matrix.android.sdk.internal.session.search.unwrapReplaceForSearch
import javax.inject.Inject
import org.matrix.android.sdk.internal.session.search.index.db.Indexed_event as IndexedEventRow

/**
 * Answers a room search from the local event index, shaped like the server-side
 * [org.matrix.android.sdk.internal.session.search.SearchTask] response so the UI is unchanged.
 */
@SessionScope
internal class LocalEventSearchTask @Inject constructor(
        private val indexStore: EventIndexStore,
        private val eventIndexer: EventIndexer,
        private val stores: SessionStores,
) {

    suspend fun search(query: ParsedSearchQuery, searchTerm: String, roomId: String, nextBatch: String?, limit: Int): SearchResult {
        if (query.tokens.isEmpty() && !query.hasFilters) {
            return SearchResult(nextBatch = null, highlights = emptyList(), results = emptyList())
        }
        // SQL prefilters on the most selective (longest) token — or scans the whole room for
        // filter-only queries. The full match happens in Kotlin, so the pagination token counts
        // candidates consumed, not matches.
        val prefilter = query.tokens.maxByOrNull { it.length }.orEmpty()
        var consumed = parseToken(nextBatch, searchTerm)
        val rows = ArrayList<IndexedEventRow>(limit)
        var moreHistory: Boolean? = null
        var backfillBudget = MAX_BACKFILL_BATCHES

        while (rows.size < limit) {
            val candidates = indexStore.search(roomId, prefilter, SCAN_BATCH_SIZE, consumed)
            if (candidates.isEmpty()) {
                // The index has no further candidates; it may simply not have crawled far enough
                // back yet — fetch older history from the server on demand (bounded per call).
                // With an after: bound, stop once the indexed history already reaches past it:
                // everything a deeper crawl could add would be older still.
                if (query.afterTs != null && (indexStore.oldestTsInRoom(roomId) ?: Long.MAX_VALUE) < query.afterTs) {
                    moreHistory = false
                    break
                }
                if (moreHistory == null) moreHistory = eventIndexer.roomHasMoreHistory(roomId)
                if (moreHistory != true || backfillBudget <= 0) break
                moreHistory = eventIndexer.backfillRoom(roomId, 1)
                backfillBudget--
                continue
            }
            for (candidate in candidates) {
                consumed++
                val matched = query.matches(
                        text = candidate.content_text,
                        sender = candidate.sender,
                        originServerTs = candidate.origin_server_ts,
                        msgtype = candidate.msgtype,
                        eventMentions = candidate.mentions?.split(' ').orEmpty(),
                )
                if (matched) {
                    rows.add(candidate)
                    if (rows.size >= limit) break
                }
            }
        }

        val results = rows.mapNotNull { row ->
            val event = tryParse(row.event_json)?.unwrapReplaceForSearch() ?: return@mapNotNull null
            event.threadDetails = stores.timelineEvent.getByRoomAndEventId(row.room_id, event.eventId ?: row.event_id)
                    ?.root
                    ?.takeIf { it.isRootThread || it.isThread() }
                    ?.asDomain()
                    ?.threadDetails
            val sender = row.sender
            val senderItem = sender?.let {
                val member = stores.roomMember.getByRoomAndUser(row.room_id, it)
                MatrixItem.UserItem(it, member?.displayName, member?.avatarUrl)
            }
            EventAndSender(event, senderItem)
        }
        return SearchResult(
                // Keep paginating while the index has more matches OR the room has uncrawled
                // history left — the next page will backfill further.
                nextBatch = if (rows.size >= limit || moreHistory == true) makeToken(consumed, searchTerm) else null,
                highlights = query.tokens,
                // The index returns newest-first; the UI expects the server mapping, which reverses.
                results = results.reversed(),
        )
    }

    private fun tryParse(json: String): Event? = runCatching { eventAdapter.fromJson(json) }.getOrNull()

    private fun makeToken(offset: Int, term: String) = "$offset:${term.hashCode()}"

    /** The UI can replay a token from a previous term; only honour it for the same term. */
    private fun parseToken(token: String?, term: String): Int {
        val parts = token?.split(':') ?: return 0
        if (parts.size != 2 || parts[1] != term.hashCode().toString()) return 0
        return parts[0].toIntOrNull() ?: 0
    }

    companion object {
        // 100 events per batch; bounds how long a single search/load-more call may take.
        private const val MAX_BACKFILL_BATCHES = 10

        // LIKE candidates fetched per index query while filling a page.
        private const val SCAN_BATCH_SIZE = 100

        private val eventAdapter = MoshiProvider.providesMoshi().adapter(Event::class.java)
    }
}
