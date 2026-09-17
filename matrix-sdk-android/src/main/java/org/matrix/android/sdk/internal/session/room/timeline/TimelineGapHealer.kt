/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.network.shouldFallBackToUnstableEndpoint
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.session.search.index.EventIndexStore
import timber.log.Timber
import javax.inject.Inject

/**
 * Detects and heals "artificial" gaps in backward pagination: a homeserver whose room ordering was
 * corrupted (e.g. by a depth-bombed room, where every post-attack event shares one topological
 * ordering and /messages walks them in arrival order) can serve a months-older event as directly
 * adjacent to a recent one, hiding everything between. A jump alone proves nothing (quiet rooms
 * exist), so it is checked against the local search index (which crawls the same API independently)
 * or, for large jumps on open timelines, against the server itself via /timestamp_to_event.
 * Recovery anchors inside the proven span via /context and lets pagination grow from its tokens.
 */
@SessionScope
internal class TimelineGapHealer @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val indexStore: EventIndexStore,
        private val contextOfEventTask: GetContextOfEventTask,
        private val tokenChunkEventPersistor: TokenChunkEventPersistor,
        private val roomAPI: RoomAPI,
        private val globalErrorReceiver: GlobalErrorReceiver,
) {

    class Detection(
            val split: TokenChunkEventPersistor.GapSplit,
            val gapNewerTs: Long,
            val gapOlderTs: Long,
            val recoveryEventIds: List<String>,
    )

    private val attemptedGaps = HashSet<String>()
    private val loggedUnprovableRooms = HashSet<String>()
    private val probedSpans = HashSet<String>()

    @Volatile
    private var timestampToEventUnsupported = false

    /**
     * Inspect a fetched backward page (newest -> oldest) for a timestamp drop the index or the
     * server can prove events exist inside. Pair zero is the join with the chunk we paginated from.
     */
    suspend fun detectArtificialGap(
            roomId: String,
            originChunkId: Long?,
            page: TokenChunkEvent,
            allowServerProbe: Boolean = false,
    ): Detection? {
        val events = page.events.filter { it.eventId != null && it.originServerTs != null }
        if (events.isEmpty()) return null
        // With no index coverage of the room nothing is locally provable, and old quiet rooms have
        // >1d gaps between most messages — without this gate a sliding-sync fill pays dozens of index
        // queries per seed page for nothing. The server probe (open timelines only) can still prove.
        val indexed = indexStore.isRoomIndexed(roomId)
        if (!indexed && !allowServerProbe) {
            DebugLog.i { "GAPDBG $roomId: page of ${events.size} not classified, room not indexed and no server probe allowed" }
            return null
        }
        val originOldestTs = originChunkId?.let {
            database.awaitDbTransaction(dispatcher) { stores.timelineEvent.minTsForChunk(it) }
        }
        // newerTs, olderTs, index of the older-side event (0 = the join pair)
        val candidates = ArrayList<Triple<Long, Long, Int>>()
        val firstTs = events.first().originServerTs!!
        if (originOldestTs != null && originOldestTs - firstTs > GAP_THRESHOLD_MS) {
            candidates += Triple(originOldestTs, firstTs, 0)
        }
        for (i in 1 until events.size) {
            val newer = events[i - 1].originServerTs!!
            val older = events[i].originServerTs!!
            if (newer - older > GAP_THRESHOLD_MS) candidates += Triple(newer, older, i)
        }
        DebugLog.i { "GAPDBG $roomId: page of ${events.size} from chunk $originChunkId (originOldestTs=$originOldestTs firstTs=$firstTs), " +
                        "indexed=$indexed probe=$allowServerProbe, ${candidates.size} candidate gap(s)" +
                        candidates.maxByOrNull { it.first - it.second }?.let { " biggest ~${(it.first - it.second) / DAY_MS}d at idx ${it.third}" }.orEmpty() }
        if (candidates.isEmpty()) return null
        // The scrambled walk order mixes timestamps, so the page itself can contain in-gap-stamped
        // events; recovering one of those is a no-op (it resolves to the chunk just written).
        val pageEventIds = events.mapTo(HashSet()) { it.eventId }
        var sawUnprovable = false
        if (indexed) {
            // Heal only the largest provable gap of the page: pagination through the recovered history
            // re-walks the rest, so other gaps get their own later detection. Cap the checks — a sparse
            // page can have a candidate between almost every event pair.
            for ((newerTs, olderTs, idx) in candidates.sortedByDescending { it.first - it.second }.take(MAX_CANDIDATE_CHECKS)) {
                val recoveryEventIds = anchorsIn(roomId, olderTs, newerTs, pageEventIds)
                if (recoveryEventIds.isEmpty()) {
                    sawUnprovable = true
                    continue
                }
                Timber.i(
                        "gap detected in $roomId: $olderTs..$newerTs " +
                                "(~${(newerTs - olderTs) / DAY_MS}d, olderIdx=$idx), recovery candidates: $recoveryEventIds"
                )
                return Detection(
                        split = TokenChunkEventPersistor.GapSplit(beforeEventId = if (idx == 0) null else events[idx].eventId),
                        gapNewerTs = newerTs,
                        gapOlderTs = olderTs,
                        recoveryEventIds = recoveryEventIds,
                )
            }
        }
        // The index couldn't prove anything: ask the server itself whether an event exists inside the
        // largest span (MSC3030 /timestamp_to_event) — server truth that works with an empty index.
        // Only for interactive timelines and only for big jumps, so fills and quiet nights stay free.
        if (allowServerProbe) {
            val (newerTs, olderTs, idx) = candidates.maxBy { it.first - it.second }
            if (newerTs - olderTs >= SERVER_PROBE_MIN_GAP_MS) {
                probeServerForEventIn(roomId, olderTs, newerTs)?.takeIf { it !in pageEventIds }?.let { eventId ->
                    Timber.i(
                            "gap proven by server in $roomId: $olderTs..$newerTs " +
                                    "(~${(newerTs - olderTs) / DAY_MS}d, olderIdx=$idx), recovering via $eventId"
                    )
                    return Detection(
                            split = TokenChunkEventPersistor.GapSplit(beforeEventId = if (idx == 0) null else events[idx].eventId),
                            gapNewerTs = newerTs,
                            gapOlderTs = olderTs,
                            recoveryEventIds = listOf(eventId),
                    )
                }
            }
        }
        // No evidence either way: could be a genuinely quiet span, or a real gap the index hasn't
        // crawled into yet — the background crawler keeps deepening it, so a later pagination can
        // still classify this.
        if (sawUnprovable && loggedUnprovableRooms.add(roomId)) {
            Timber.i("unclassified >1d pagination jump(s) in $roomId (no indexed events inside)")
        }
        return null
    }

    /**
     * Prefer interior anchors near the midpoint; boundary-adjacent events do not bridge the gap.
     * Spread fallback anchors to tolerate per-event visibility failures.
     */
    private suspend fun anchorsIn(roomId: String, olderTs: Long, newerTs: Long, exclude: Set<String?>): List<String> {
        val margin = ((newerTs - olderTs) / 10).coerceAtLeast(MIN_ANCHOR_MARGIN_MS)
        val innerOlder = olderTs + margin
        val innerNewer = newerTs - margin
        if (innerNewer <= innerOlder) return emptyList()
        val midTs = (innerOlder + innerNewer) / 2
        val inGap = indexStore.eventsNearestTsInRange(roomId, innerOlder, innerNewer, midTs, 4) +
                indexStore.eventsInTsRange(roomId, innerOlder, innerNewer, 2) +
                indexStore.eventsInTsRange(roomId, innerOlder, innerNewer, 2, newestFirst = false)
        if (inGap.isEmpty()) {
            DebugLog.i { "GAPDBG $roomId: index knows nothing inside $innerOlder..$innerNewer (span ~${(newerTs - olderTs) / DAY_MS}d)" }
            return emptyList()
        }
        // The span's only known events can be the ones the page just delivered: nothing to recover then,
        // the walk already has them.
        return inGap.map { it.first }.distinct().filter { it !in exclude }.take(4)
    }

    /**
     * @return an event id the server holds strictly inside (olderTs, newerTs), or null. Only visible
     * events are returned by the endpoint, so a hit is also /context-able in principle.
     */
    private suspend fun probeServerForEventIn(roomId: String, olderTs: Long, newerTs: Long): String? {
        if (!probedSpans.add("$roomId|$olderTs|$newerTs")) return null
        // Ask about the middle of the span, for the same reason the index candidates are taken from
        // there: an event at the boundary is next to history we already hold and bridges nothing.
        val found = eventAtOrBefore(roomId, (olderTs + newerTs) / 2) ?: return null
        return found.eventId.takeIf { found.originServerTs in (olderTs + 1) until newerTs }
    }

    /** The event the server holds nearest [ts], looking backwards. Null when there is none, or no support. */
    private suspend fun eventAtOrBefore(roomId: String, ts: Long): TimestampToEventResponse? {
        if (timestampToEventUnsupported) return null
        return try {
            try {
                executeRequest(globalErrorReceiver) { roomAPI.getEventForTimestamp(roomId, ts, "b") }
            } catch (failure: Throwable) {
                if (failure.isNoEventFound()) return null
                if (!failure.shouldFallBackToUnstableEndpoint()) throw failure
                executeRequest(globalErrorReceiver) { roomAPI.getEventForTimestampUnstable(roomId, ts, "b") }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            when {
                failure.isNoEventFound() -> Unit
                failure.shouldFallBackToUnstableEndpoint() -> {
                    // Neither prefix exists on this server; stop asking for the session.
                    timestampToEventUnsupported = true
                    Timber.i("timestamp_to_event unsupported by server, disabling timestamp walks")
                }
                else -> DebugLog.i { "GAPDBG $roomId: timestamp_to_event failed: ${failure.message}" }
            }
            null
        }
    }

    /**
     * Use MSC3030 timestamp lookups when topological context tokens skip history.
     * Returns the number of recovered windows.
     */
    suspend fun fillBackwardByTimestamp(roomId: String, boundaryChunkId: Long, maxSteps: Int = TS_WALK_MAX_STEPS): Int {
        if (timestampToEventUnsupported) {
            DebugLog.i { "GAPDBG $roomId: no timestamp walk, the server has no timestamp_to_event" }
            return 0
        }
        var chunkId = boundaryChunkId
        var ts = oldestTsOf(chunkId) ?: return 0.also { DebugLog.i { "GAPDBG $roomId: no timestamp walk, chunk $boundaryChunkId has no timestamps" } }
        var filled = 0
        DebugLog.i { "GAPDBG $roomId: timestamp walk from chunk $chunkId, below $ts" }
        while (filled < maxSteps) {
            val found = eventAtOrBefore(roomId, ts - 1)
            if (found == null) {
                DebugLog.i { "GAPDBG $roomId: timestamp walk stops, the server names no event before $ts" }
                break
            }
            if (found.originServerTs >= ts) {
                DebugLog.i { "GAPDBG $roomId: timestamp walk stops, ${found.eventId} at ${found.originServerTs} is not older than $ts" }
                break
            }
            val owner = database.awaitDbTransaction(dispatcher) {
                stores.chunk.findMainChunkIdIncludingEvent(roomId, found.eventId)
            }
            if (owner != null) {
                // Already stored: the server has named this as the event immediately below the boundary,
                // so that region is provably the boundary's past. Take it in, or its history stays out of
                // the timeline however often the walk proves this.
                if (owner != chunkId && tokenChunkEventPersistor.spliceBackward(chunkId, owner)) {
                    filled++
                    DebugLog.i { "GAPDBG $roomId: timestamp walk took stored chunk $owner into $chunkId at ${found.originServerTs}" }
                } else {
                    DebugLog.i { "GAPDBG $roomId: timestamp walk met stored chunk $owner under $chunkId at ${found.originServerTs}" }
                }
                break
            }
            try {
                contextOfEventTask.execute(GetContextOfEventTask.Params(roomId, found.eventId, TS_WALK_CONTEXT_WINDOW))
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                DebugLog.w { "GAPDBG $roomId: timestamp walk /context for ${found.eventId} failed: ${failure.message}" }
                break
            }
            val landed = database.awaitDbTransaction(dispatcher) {
                stores.chunk.findMainChunkIdIncludingEvent(roomId, found.eventId)
            }
            if (landed == null || landed == chunkId) {
                DebugLog.i { "GAPDBG $roomId: timestamp walk stops, /context for ${found.eventId} landed on $landed" }
                break
            }
            // The window straddles the boundary it was fetched for; its newer half belongs inside.
            val absorbed = database.awaitDbTransaction(dispatcher) {
                // The window straddles the boundary: its newer half is simply re-parented, since the order
                // of those rows is their timestamp either way.
                stores.timelineEvent.minTsForChunk(chunkId)
                        ?.let { boundaryOldest -> stores.timelineEvent.moveRowsFromTs(landed, chunkId, boundaryOldest) }
                        ?: 0
            }
            tokenChunkEventPersistor.spliceBackward(chunkId, landed)
            val landedOldest = oldestTsOf(landed) ?: break
            if (landedOldest >= ts) {
                DebugLog.i { "GAPDBG $roomId: timestamp walk stops, chunk $landed reaches no further back than $ts" }
                break
            }
            if (absorbed > 0) DebugLog.i { "GAPDBG $roomId: absorbed $absorbed straddling row(s) of $landed into $chunkId" }
            filled++
            DebugLog.i { "GAPDBG $roomId: timestamp walk filled chunk $landed under $chunkId (down to $landedOldest)" }
            chunkId = landed
            ts = landedOldest
        }
        return filled
    }

    private suspend fun oldestTsOf(chunkId: Long): Long? =
            database.awaitDbTransaction(dispatcher) { stores.timelineEvent.minTsForChunk(chunkId) }

    private fun Throwable.isNoEventFound(): Boolean =
            this is Failure.ServerError && error.code == MatrixError.M_NOT_FOUND

    /** Recover interior anchors, then try the remaining holes on either side within the request budget. */
    suspend fun recoverAfterPersist(roomId: String, detection: Detection?) {
        detection ?: return
        val newerChunkId = detection.split.newerChunkId ?: return
        if (attemptedGaps.count { it.startsWith("$roomId|") } >= MAX_RECOVERIES_PER_ROOM) return
        val gapKey = "$roomId|${detection.gapOlderTs}|${detection.gapNewerTs}"
        if (!attemptedGaps.add(gapKey)) return

        // Holes still to bridge: the chunk to splice under, and the span between it and the far side.
        val holes = ArrayDeque<Triple<Long, Long, Long>>()
        holes += Triple(newerChunkId, detection.gapOlderTs, detection.gapNewerTs)
        var anchors = MAX_ANCHORS_PER_GAP
        var bridged = 0
        while (holes.isNotEmpty() && anchors > 0) {
            val (holeChunkId, olderTs, newerTs) = holes.removeFirst()
            if (newerTs - olderTs <= GAP_THRESHOLD_MS) continue
            val candidates = if (bridged == 0) detection.recoveryEventIds else anchorsIn(roomId, olderTs, newerTs, emptySet())
            if (candidates.isEmpty()) continue
            anchors--
            val recovered = anchorUnder(roomId, holeChunkId, olderTs, newerTs, candidates, detection.split.olderChunkId) ?: continue
            bridged++
            val span = database.awaitDbTransaction(dispatcher) {
                stores.timelineEvent.minTsForChunk(recovered) to stores.timelineEvent.maxTsForChunk(recovered)
            }
            val (recoveredOldest, recoveredNewest) = span
            if (recoveredOldest == null || recoveredNewest == null) continue
            // What the anchor did not cover, on either side of it.
            holes += Triple(holeChunkId, recoveredNewest, newerTs)
            holes += Triple(recovered, olderTs, recoveredOldest)
        }
        if (bridged == 0) {
            Timber.w("recovery for $gapKey: nothing anchored (${detection.recoveryEventIds.size} candidates)")
        } else {
            Timber.i("recovery for $gapKey: bridged with $bridged anchor(s)")
        }
    }

    /** Splice one of [candidates] under [holeChunkId]. @return the chunk that landed, or null. */
    private suspend fun anchorUnder(
            roomId: String,
            holeChunkId: Long,
            olderTs: Long,
            newerTs: Long,
            candidates: List<String>,
            olderSideChunkId: Long?,
    ): Long? {
        for (recoveryEventId in candidates) {
            try {
                // Already stored elsewhere: that chunk IS the way into the gap — splice, no fetch.
                val existingOwner = database.awaitDbTransaction(dispatcher) {
                    stores.chunk.findMainChunkIdIncludingEvent(roomId, recoveryEventId)
                }
                if (existingOwner == holeChunkId || existingOwner == olderSideChunkId) continue
                if (existingOwner != null) {
                    val joined = tokenChunkEventPersistor.spliceBackward(holeChunkId, existingOwner)
                    DebugLog.i { "GAPDBG $roomId: ${if (joined) "took" else "could not take"} existing chunk $existingOwner" +
                                    " into $holeChunkId via $recoveryEventId ($olderTs..$newerTs)" }
                    if (joined) return existingOwner else continue
                }
                contextOfEventTask.execute(GetContextOfEventTask.Params(roomId, recoveryEventId, RECOVERY_CONTEXT_WINDOW))
                val recoveredChunkId = database.awaitDbTransaction(dispatcher) {
                    stores.chunk.findMainChunkIdIncludingEvent(roomId, recoveryEventId)
                }
                if (recoveredChunkId == null || recoveredChunkId == holeChunkId) {
                    DebugLog.w { "GAPDBG $roomId: /context for $recoveryEventId landed nowhere usable (chunk=$recoveredChunkId)" }
                    continue
                }
                val joined = tokenChunkEventPersistor.spliceBackward(holeChunkId, recoveredChunkId)
                DebugLog.i { "GAPDBG $roomId: ${if (joined) "took" else "could not take"} chunk $recoveredChunkId" +
                                " into $holeChunkId via $recoveryEventId ($olderTs..$newerTs)" }
                if (joined) return recoveredChunkId else continue
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                // The pre-recovery links are intact, so failing here just leaves today's behavior.
                DebugLog.w { "GAPDBG $roomId: recovery via $recoveryEventId failed: ${failure.message}" }
            }
        }
        return null
    }

    companion object {
        private const val DAY_MS = 24 * 3600 * 1000L
        private const val GAP_THRESHOLD_MS = DAY_MS
        private const val MAX_RECOVERIES_PER_ROOM = 12

        // Each anchor buys a context window; limit requests per detected gap.
        private const val MAX_ANCHORS_PER_GAP = 4

        // Keep anchors clear of both boundaries; inside this an anchor is adjacent to what we already hold.
        private const val MIN_ANCHOR_MARGIN_MS = 6 * 3600 * 1000L
        private const val MAX_CANDIDATE_CHECKS = 4
        private const val SERVER_PROBE_MIN_GAP_MS = 7 * DAY_MS

        // A real window rather than the lone event: a 1-event island advances the timeline one
        // message per recovery, and pagination continues from the window's own tokens.
        private const val RECOVERY_CONTEXT_WINDOW = 50

        // One timestamp walk covers a few screenfuls; scrolling on asks for the next. Each step is two
        // requests, so this is the trade between a responsive scroll and round trips.
        private const val TS_WALK_MAX_STEPS = 6
        private const val TS_WALK_CONTEXT_WINDOW = 50
    }
}
