/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.search

import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.debug.OperationDiagnostics
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.search.MatrixSearchQuery
import org.matrix.android.sdk.api.session.search.SearchResult
import org.matrix.android.sdk.api.session.search.SearchService
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.session.room.peeking.PeekedRoomManager
import org.matrix.android.sdk.internal.session.search.index.EventIndexer
import org.matrix.android.sdk.internal.session.search.index.LocalEventSearchTask
import javax.inject.Inject

internal class DefaultSearchService @Inject constructor(
        private val searchTask: SearchTask,
        private val localEventSearchTask: LocalEventSearchTask,
        private val eventIndexer: EventIndexer,
        private val stores: SessionStores,
        private val peekedRoomManager: PeekedRoomManager,
        private val messagesCrawlSearchTask: MessagesCrawlSearchTask,
) : SearchService {

    override fun parseQuery(searchTerm: String): MatrixSearchQuery {
        val parsed = searchTerm.takeIf { it.isNotBlank() }?.let { SearchQueryParser.parse(it) }
        return object : MatrixSearchQuery {
            override fun matches(event: Event) = parsed == null || parsed.matchesEvent(event)
        }
    }

    override suspend fun search(
            searchTerm: String,
            roomId: String,
            nextBatch: String?,
            orderByRecent: Boolean,
            limit: Int,
            beforeLimit: Int,
            afterLimit: Int,
            includeProfile: Boolean
    ): SearchResult {
        val diagnosticSpan = OperationDiagnostics.start("SEARCHDBG", "search") { "room=${OperationDiagnostics.id(roomId)} queryLength=${searchTerm.length} paginated=${nextBatch != null} limit=$limit" }
        try {
            val query = SearchQueryParser.parse(searchTerm)

            // A peeked (un-joined) room: the server refuses /search without membership, so crawl
            // /messages and match locally.
            if (peekedRoomManager.get(roomId) != null) {
                DebugLog.i { "SEARCHDBG backend=crawl reason=peek-or-server-incompatible" }
                return messagesCrawlSearchTask.search(query, searchTerm, roomId, nextBatch, limit)
            }

            // The local index answers for the rooms it holds: encrypted ones (the server cannot search
            // them), and unencrypted ones unless the user opted for server search.
            val encrypted = stores.roomSummary.isEncrypted(roomId)
            if (eventIndexer.isEnabled() && (encrypted || eventIndexer.includesUnencryptedRooms())) {
                DebugLog.i { "SEARCHDBG backend=local encrypted=$encrypted" }
                return localEventSearchTask.search(query, searchTerm, roomId, nextBatch, limit)
            }
            // The server needs a search term and only searches current rooms; an unencrypted room's history
            // can still be crawled and matched here instead.
            val joined = stores.room.get(roomId)?.membership == Membership.JOIN
            if (!encrypted && (query.tokens.isEmpty() || !joined)) {
                DebugLog.i { "SEARCHDBG backend=crawl reason=peek-or-server-incompatible" }
                return messagesCrawlSearchTask.search(query, searchTerm, roomId, nextBatch, limit)
            }
            if (query.tokens.isEmpty()) {
                return SearchResult(nextBatch = null, highlights = emptyList(), results = emptyList())
            }
            DebugLog.i { "SEARCHDBG backend=server reason=local-index-unavailable-or-disabled" }
            val result = searchTask.execute(
                    SearchTask.Params(
                            // Quotes mean nothing to the server; send the bare tokens for recall and
                            // enforce the verbatim/phrase semantics client-side below.
                            searchTerm = query.tokens.joinToString(" "),
                            roomId = roomId,
                            nextBatch = nextBatch,
                            orderByRecent = orderByRecent,
                            limit = limit,
                            beforeLimit = beforeLimit,
                            afterLimit = afterLimit,
                            includeProfile = includeProfile
                    )
            )
            // Server matching stems words ("looks" matches "look") and knows nothing of our filters;
            // refilter with the full query semantics so both search backends behave identically.
            // Highlights become our tokens rather than the server's stems, which is what the UI bolds.
            DebugLog.i { "SEARCHDBG server-result count=${result.results.orEmpty().size} hasNext=${result.nextBatch != null}" }
            return result.copy(
                    results = result.results
                            ?.filter { query.matchesServerEvent(it.event) }
                            ?.map { it.copy(event = it.event.unwrapReplaceForSearch()) },
                    highlights = query.tokens,
            )
        } catch (diagnosticFailure: Throwable) {
            diagnosticSpan?.fail(diagnosticFailure)
            throw diagnosticFailure
        } finally {
            diagnosticSpan?.finish("completed")
        }
    }

    private fun ParsedSearchQuery.matchesServerEvent(event: Event): Boolean {
        // Edited content first, like the UI displays it.
        @Suppress("UNCHECKED_CAST")
        val content = (event.content?.get("m.new_content") as? Content) ?: event.content
        return matches(
                text = content?.get("body") as? String ?: "",
                sender = event.senderId,
                originServerTs = event.originServerTs ?: 0L,
                msgtypes = searchMsgTypes(event.getClearType(), content),
                eventMentions = extractMentionedUserIds(event.content),
        )
    }
}
