/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.timeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.settings.LightweightSettingsStorage
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
        private val lightweightSettingsStorage: LightweightSettingsStorage,
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
        if (!lightweightSettingsStorage.isTimelineGapHealingEnabled()) return null
        val events = page.events.filter { it.eventId != null && it.originServerTs != null }
        if (events.isEmpty()) return null
        // With no index coverage of the room nothing is locally provable, and old quiet rooms have
        // >1d gaps between most messages — without this gate a sliding-sync fill pays dozens of index
        // queries per seed page for nothing. The server probe (open timelines only) can still prove.
        val indexed = indexStore.isRoomIndexed(roomId)
        if (!indexed && !allowServerProbe) return null
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
                // Recovery candidates from both ends of the span: a server with broken per-event
                // visibility (403 on /context) often locks out a whole era, so spread the attempts.
                val inGap = indexStore.eventsInTsRange(roomId, olderTs, newerTs, 6) +
                        indexStore.eventsInTsRange(roomId, olderTs, newerTs, 6, newestFirst = false)
                if (inGap.isEmpty()) {
                    sawUnprovable = true
                    continue
                }
                val recoveryEventIds = inGap.map { it.first }.distinct().filter { it !in pageEventIds }.take(4)
                if (recoveryEventIds.isEmpty()) {
                    // The span's only known events are the ones this page just delivered: nothing to
                    // recover, the walk already has them.
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
     * @return an event id the server holds strictly inside (olderTs, newerTs), or null. Only visible
     * events are returned by the endpoint, so a hit is also /context-able in principle.
     */
    private suspend fun probeServerForEventIn(roomId: String, olderTs: Long, newerTs: Long): String? {
        if (timestampToEventUnsupported) return null
        if (!probedSpans.add("$roomId|$olderTs|$newerTs")) return null
        return try {
            val response = try {
                executeRequest(globalErrorReceiver) { roomAPI.getEventForTimestamp(roomId, newerTs - 1, "b") }
            } catch (failure: Throwable) {
                if (failure.isNoEventFound()) return null
                if (!failure.shouldFallBackToUnstableEndpoint()) throw failure
                executeRequest(globalErrorReceiver) { roomAPI.getEventForTimestampUnstable(roomId, newerTs - 1, "b") }
            }
            response.eventId.takeIf { response.originServerTs in (olderTs + 1) until newerTs }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            when {
                failure.isNoEventFound() -> Unit
                failure.shouldFallBackToUnstableEndpoint() -> {
                    // Neither prefix exists on this server; stop asking for the session.
                    timestampToEventUnsupported = true
                    Timber.i("timestamp_to_event unsupported by server, disabling probe")
                }
                else -> Timber.d("timestamp_to_event probe failed for $roomId: ${failure.message}")
            }
            null
        }
    }

    private fun Throwable.isNoEventFound(): Boolean =
            this is Failure.ServerError && error.code == MatrixError.M_NOT_FOUND

    /** After the page was persisted around the gap, fetch an in-gap event and splice it in. */
    suspend fun recoverAfterPersist(roomId: String, detection: Detection?) {
        detection ?: return
        val newerChunkId = detection.split.newerChunkId ?: return
        if (attemptedGaps.count { it.startsWith("$roomId|") } >= MAX_RECOVERIES_PER_ROOM) return
        val gapKey = "$roomId|${detection.gapOlderTs}|${detection.gapNewerTs}"
        if (!attemptedGaps.add(gapKey)) return
        for (recoveryEventId in detection.recoveryEventIds) {
            try {
                // Already stored elsewhere: that chunk IS the way into the gap — splice, no fetch.
                val existingOwner = database.awaitDbTransaction(dispatcher) {
                    stores.chunk.findMainChunkIdIncludingEvent(roomId, recoveryEventId)
                }
                if (existingOwner == newerChunkId || existingOwner == detection.split.olderChunkId) continue
                if (existingOwner != null) {
                    tokenChunkEventPersistor.spliceBackward(newerChunkId, existingOwner)
                    Timber.i("recovery for $gapKey: spliced existing chunk $existingOwner under $newerChunkId via $recoveryEventId")
                    return
                }
                contextOfEventTask.execute(GetContextOfEventTask.Params(roomId, recoveryEventId, RECOVERY_CONTEXT_WINDOW))
                val recoveredChunkId = database.awaitDbTransaction(dispatcher) {
                    stores.chunk.findMainChunkIdIncludingEvent(roomId, recoveryEventId)
                }
                if (recoveredChunkId == null || recoveredChunkId == newerChunkId) {
                    Timber.w("recovery for $gapKey: /context landed nowhere usable (chunk=$recoveredChunkId)")
                    continue
                }
                tokenChunkEventPersistor.spliceBackward(newerChunkId, recoveredChunkId)
                Timber.i("recovery for $gapKey: spliced chunk $recoveredChunkId under $newerChunkId via $recoveryEventId")
                return
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                // The pre-recovery links are intact, so failing here just leaves today's behavior.
                Timber.w("recovery via $recoveryEventId failed for $gapKey: ${failure.message}")
            }
        }
        Timber.w("recovery for $gapKey: all ${detection.recoveryEventIds.size} candidates failed")
    }

    companion object {
        private const val DAY_MS = 24 * 3600 * 1000L
        private const val GAP_THRESHOLD_MS = DAY_MS
        private const val MAX_RECOVERIES_PER_ROOM = 5
        private const val MAX_CANDIDATE_CHECKS = 4
        private const val SERVER_PROBE_MIN_GAP_MS = 7 * DAY_MS

        // A real window rather than the lone event: a 1-event island advances the timeline one
        // message per recovery, and pagination continues from the window's own tokens.
        private const val RECOVERY_CONTEXT_WINDOW = 50
    }
}
