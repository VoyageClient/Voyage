/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.search.index

import kotlinx.coroutines.CancellationException
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.debug.OperationDiagnostics
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.crypto.CryptoService
import org.matrix.android.sdk.api.session.crypto.model.OlmDecryptionResult
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.search.EventAndSender
import org.matrix.android.sdk.api.session.search.SearchResult
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.mapper.overriddenUserItem
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.search.ParsedSearchQuery
import org.matrix.android.sdk.internal.session.search.unwrapReplaceForSearch
import timber.log.Timber
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
        private val cryptoService: dagger.Lazy<CryptoService>,
) {

    suspend fun search(query: ParsedSearchQuery, searchTerm: String, roomId: String, nextBatch: String?, limit: Int): SearchResult {
        val diagnosticSpan = OperationDiagnostics.start("SEARCHDBG", "local-search") { "room=${OperationDiagnostics.id(roomId)} queryLength=${searchTerm.length} limit=$limit" }
        try {
            val emptyRange = query.afterTs != null && query.beforeTs != null && query.afterTs >= query.beforeTs
            if (query.tokens.isEmpty() && !query.hasFilters || emptyRange) {
                return SearchResult(nextBatch = null, highlights = emptyList(), results = emptyList())
            }
            // SQL prefilters on the most selective (longest) token, and on the date bounds; the full match
            // happens in Kotlin.
            val prefilter = query.tokens.maxByOrNull { it.length }.orEmpty()
            val lowerBound = query.afterTs ?: Long.MIN_VALUE
            var cursor = Cursor.parse(nextBatch, searchTerm) ?: Cursor(query.beforeTs ?: Long.MAX_VALUE, "")
            val rows = ArrayList<IndexedEventRow>(limit)
            // Only what lies above this is vouched for: below it are just the stretches the sweep found
            // cached, with gaps the crawl has yet to fill.
            var floor = eventIndexer.completeSince(roomId)
            var backfills = 0
            var exhausted = false

            while (rows.size < limit) {
                val candidates = indexStore.search(roomId, prefilter, cursor.ts, cursor.eventId, maxOf(floor, lowerBound), SCAN_BATCH_SIZE)
                for (candidate in candidates) {
                    cursor = Cursor(candidate.origin_server_ts, candidate.event_id)
                    val matched = query.matches(
                            text = candidate.content_text,
                            sender = candidate.sender,
                            originServerTs = candidate.origin_server_ts,
                            // Stored space-joined: a gallery carries its items' types alongside its own.
                            msgtypes = candidate.msgtype?.split(' ').orEmpty(),
                            eventMentions = candidate.mentions?.split(' ').orEmpty(),
                    )
                    if (matched) {
                        rows.add(candidate)
                        if (rows.size >= limit) break
                    }
                }
                if (rows.size >= limit || candidates.size == SCAN_BATCH_SIZE) continue
                if (floor <= lowerBound) {
                    exhausted = true
                    break
                }
                // Read down to the floor: crawl further back (bounded per call; the next page goes on).
                if (backfills >= MAX_BACKFILL_BATCHES) break
                backfills++
                floor = try {
                    eventIndexer.backfillRoomOrThrow(roomId)
                    eventIndexer.completeSince(roomId)
                } catch (e: CancellationException) {
                    throw e
                } catch (failure: Throwable) {
                    // Offline, say: answer from whatever is cached, however patchy, rather than fail the search.
                    DebugLog.w { "SEARCHDBG fallback=cached-index reason=backfill-failed error=${failure.javaClass.simpleName}" }
                    Timber.w(failure, "LocalEventSearchTask: backfill failed, searching the cached index")
                    Long.MIN_VALUE
                }
            }

            val roomEncrypted by lazy { stores.roomSummary.isEncrypted(roomId) }
            val results = rows.mapNotNull { row ->
                val clearEvent = tryParse(row.event_json)?.unwrapReplaceForSearch() ?: return@mapNotNull null
                val localTimelineEvent = stores.timelineEvent.getByRoomAndEventId(row.room_id, clearEvent.eventId ?: row.event_id)
                val localRootEntity = localTimelineEvent?.root
                val localRoot = localRootEntity?.asDomain()
                // NULL: indexed before the flag existed.
                val wasEncrypted = row.encrypted?.let { it != 0L } ?: localRoot?.isEncrypted() ?: roomEncrypted
                val event = when {
                    clearEvent.isRedacted() -> clearEvent
                    clearEvent.type == EventType.ENCRYPTED -> clearEvent.alsoTryDecrypt(localRoot?.mxDecryptionResult)
                    wasEncrypted -> clearEvent.asDecrypted(localRoot?.mxDecryptionResult)
                    else -> clearEvent
                }
                event.threadDetails = localRootEntity
                        ?.takeIf { it.isRootThread || it.isThread() }
                        ?.let { localRoot?.threadDetails }
                val sender = row.sender
                val senderItem = sender?.let {
                    // The name and avatar the sender had when the event was sent, as the timeline shows
                    // them; the current member state is only a fallback for uncached events.
                    if (localTimelineEvent?.senderName != null || localTimelineEvent?.senderAvatar != null) {
                        overriddenUserItem(it, localTimelineEvent.senderName, localTimelineEvent.senderAvatar)
                    } else {
                        val member = stores.roomMember.getByRoomAndUser(row.room_id, it)
                        overriddenUserItem(it, member?.displayName, member?.avatarUrl)
                    }
                }
                EventAndSender(event, senderItem)
            }
            DebugLog.i { "SEARCHDBG local-result scannedMatches=${rows.size} rendered=${results.size} backfills=$backfills exhausted=$exhausted coverageFloor=$floor" }
            return SearchResult(
                    // Keep paginating while the index has more matches or the floor has yet to reach the
                    // lower bound; the next page crawls further.
                    nextBatch = if (rows.size >= limit || !exhausted) cursor.encode(searchTerm) else null,
                    highlights = query.tokens,
                    // The index returns newest-first; the UI expects the server mapping, which reverses.
                    results = results.reversed(),
            )
        } catch (diagnosticFailure: Throwable) {
            diagnosticSpan?.fail(diagnosticFailure)
            throw diagnosticFailure
        } finally {
            diagnosticSpan?.finish("completed")
        }
    }

    private fun tryParse(json: String): Event? = runCatching { eventAdapter.fromJson(json) }.getOrNull()

    // Unwrapped, the UI reads the hit as sent in the clear. Wire content stays clear so m.relates_to resolves.
    private fun Event.asDecrypted(localResult: OlmDecryptionResult?): Event {
        val payload = mapOf<String, Any>("type" to type.orEmpty(), "content" to content.orEmpty())
        return copyAll(type = EventType.ENCRYPTED).also {
            it.mxDecryptionResult = localResult?.copy(payload = payload) ?: OlmDecryptionResult(payload = payload, isSafe = true)
        }
    }

    /**
     * A row kept while the event could not be decrypted: the keys may have arrived since. A success also
     * replaces the row, so the event becomes searchable by its text.
     */
    private suspend fun Event.alsoTryDecrypt(localResult: OlmDecryptionResult?): Event {
        if (localResult != null) return also { it.mxDecryptionResult = localResult }
        val result = tryOrNull { cryptoService.get().decryptEvent(this, "") } ?: return this
        mxDecryptionResult = OlmDecryptionResult(
                payload = result.clearEvent,
                senderKey = result.senderCurve25519Key,
                keysClaimed = result.claimedEd25519Key?.let { mapOf("ed25519" to it) },
                forwardingCurve25519KeyChain = result.forwardingCurve25519KeyChain,
                verificationState = result.messageVerificationState,
        )
        eventIndexer.onEventsDecrypted(listOf(this to result))
        return this
    }

    /** Keyset position: the last row read. Only honoured for the term it was made for, as the UI can replay an old one. */
    private data class Cursor(val ts: Long, val eventId: String) {
        fun encode(term: String) = "${term.hashCode()}|$ts|$eventId"

        companion object {
            fun parse(token: String?, term: String): Cursor? {
                val parts = token?.split('|', limit = 3) ?: return null
                if (parts.size != 3 || parts[0] != term.hashCode().toString()) return null
                return Cursor(parts[1].toLongOrNull() ?: return null, parts[2])
            }
        }
    }

    companion object {
        // 100 events per batch; bounds how long a single search/load-more call may take.
        private const val MAX_BACKFILL_BATCHES = 10

        // LIKE candidates fetched per index query while filling a page.
        private const val SCAN_BATCH_SIZE = 100

        private val eventAdapter = MoshiProvider.providesMoshi().adapter(Event::class.java)
    }
}
