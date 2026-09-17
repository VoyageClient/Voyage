/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.internal.database.model.ChunkEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.Chunk as ChunkRow

/** Room history is stored as independent ranges, each ordered by (ts, event_id). */
internal class ChunkSqlStore(private val database: SessionSqlDatabase) {

    private val queries get() = database.chunkQueries

    // Do not split an unfillable gap again until the next session; its older history must remain reachable.
    private val unfillableGaps = java.util.Collections.synchronizedSet(HashSet<String>())

    fun markGapUnfillable(roomId: String, olderSideMaxTs: Long) {
        unfillableGaps.add("$roomId|$olderSideMaxTs")
    }

    private fun isGapUnfillable(roomId: String, olderSideMaxTs: Long) = "$roomId|$olderSideMaxTs" in unfillableGaps

    // Boundaries a timestamp walk already failed to fill, for the rest of the session: the walk costs a
    // /timestamp_to_event and a /context each time and its answer for a given boundary does not change,
    // so repeating it only delays the timeline every time the room is opened.
    private val unhealableBoundaries = java.util.Collections.synchronizedSet(HashSet<String>())

    fun markBoundaryUnhealable(roomId: String, boundaryKey: String) {
        unhealableBoundaries.add("$roomId|$boundaryKey")
    }

    fun isBoundaryUnhealable(roomId: String, boundaryKey: String) = "$roomId|$boundaryKey" in unhealableBoundaries

    fun forgetUnhealableBoundaries(roomId: String) {
        synchronized(unhealableBoundaries) { unhealableBoundaries.removeAll { it.startsWith("$roomId|") } }
    }

    fun getById(id: Long): ChunkRow? = queries.selectById(id).executeAsOneOrNull()

    fun getByRoom(roomId: String): List<ChunkRow> = queries.selectByRoom(roomId).executeAsList()

    fun lastForward(roomId: String): ChunkRow? = queries.selectLastForward(roomId).executeAsOneOrNull()

    fun lastForwardThread(roomId: String, rootThreadEventId: String): ChunkRow? =
            queries.selectLastForwardThread(roomId, rootThreadEventId).executeAsOneOrNull()

    fun findByTokens(roomId: String, prevToken: String?, nextToken: String?): ChunkRow? =
            queries.selectByTokens(roomId, prevToken, nextToken).executeAsOneOrNull()

    // A null token means "no boundary" (the live range's next_token, a reached-start range's prev_token)
    // and must never match another such range: only a real token identifies a shared boundary.
    fun findByNextToken(roomId: String, nextToken: String?): ChunkRow? =
            nextToken?.let { queries.selectByNextToken(roomId, it).executeAsOneOrNull() }

    fun findByPrevToken(roomId: String, prevToken: String?): ChunkRow? =
            prevToken?.let { queries.selectByPrevToken(roomId, it).executeAsOneOrNull() }

    fun insert(
            roomId: String,
            prevToken: String?,
            nextToken: String?,
            isLastForward: Boolean,
            isLastBackward: Boolean,
            rootThreadEventId: String?,
            isLastForwardThread: Boolean,
    ): Long {
        queries.insert(
                room_id = roomId,
                prev_token = prevToken,
                next_token = nextToken,
                is_last_forward = if (isLastForward) 1L else 0L,
                is_last_backward = if (isLastBackward) 1L else 0L,
                root_thread_event_id = rootThreadEventId,
                is_last_forward_thread = if (isLastForwardThread) 1L else 0L,
        )
        return queries.lastInsertRowId().executeAsOne()
    }

    fun updateTokens(id: Long, prevToken: String?, nextToken: String?) = queries.updateTokens(prevToken, nextToken, id)

    fun updateNextToken(id: Long, nextToken: String?) = queries.updateNextToken(nextToken, id)

    fun updatePrevToken(id: Long, prevToken: String?) = queries.updatePrevToken(prevToken, id)

    fun findChunkIdIncludingEvent(roomId: String, eventId: String): Long? =
            queries.selectChunkIdIncludingEvent(roomId, eventId).executeAsOneOrNull()

    /** Like [findChunkIdIncludingEvent] but ignores thread ranges, which duplicate main events by design. */
    fun findMainChunkIdIncludingEvent(roomId: String, eventId: String): Long? =
            queries.selectMainChunkIdIncludingEvent(roomId, eventId).executeAsOneOrNull()?.chunk_id

    fun rangeBelow(roomId: String, rangeId: Long): Long? =
            queries.selectRangeBelow(roomId, rangeId).executeAsOneOrNull()?.chunk_id

    fun mergeInto(winnerId: Long, loserId: Long) {
        if (winnerId == loserId) return
        val winner = getById(winnerId) ?: return
        val loser = getById(loserId) ?: return
        val loserSpan = spanOf(loserId)
        val winnerSpan = spanOf(winnerId)

        database.timelineEventQueries.selectByChunk(loserId).executeAsList().forEach { row ->
            if (database.timelineEventQueries.selectInChunkByEventId(winnerId, row.event_id).executeAsOneOrNull() != null) {
                database.timelineEventQueries.deleteById(row.id)
            } else {
                database.timelineEventQueries.updateChunkId(winnerId, row.id)
            }
        }

        // Whichever side reached further keeps its frontier, or pagination would stop at the seam. With no
        // spans to compare (an empty range) the loser's token is only taken where the winner has none.
        val loserIsOlder = loserSpan != null && winnerSpan != null && loserSpan.first < winnerSpan.first
        if (loserIsOlder || (winnerSpan == null && loser.prev_token != null)) {
            updatePrevToken(winnerId, loser.prev_token)
        }
        if (loser.is_last_backward != 0L && (loserIsOlder || winnerSpan == null)) setLastBackward(winnerId, true)
        val loserIsNewer = loserSpan != null && winnerSpan != null && loserSpan.second > winnerSpan.second
        if (winner.is_last_forward == 0L && (loserIsNewer || winnerSpan == null)) {
            updateNextToken(winnerId, loser.next_token)
            if (loser.is_last_forward != 0L) setLastForward(winnerId, true)
        }

        deleteById(loserId)
    }

    /**
     * Fold every range that shares an event with [rangeId] into one, since a shared event proves they
     * describe the same history. @return the surviving range, or null if nothing shared any event.
     */
    fun mergeRangesSharingEvents(roomId: String, rangeId: Long): Long? {
        // Walks from the survivor each pass: the range the caller named can itself be the one folded away.
        var current = rangeId
        var merged = 0
        while (true) {
            val other = queries.selectRangeSharingEvent(roomId, current).executeAsOneOrNull()?.chunk_id
                    ?: return current.takeIf { merged > 0 }
            current = mergeKeepingLarger(current, other)
            merged++
            if (merged > MAX_MERGE_PASSES) {
                DebugLog.e { "CHUNKDBG $roomId gave up merging into #$current after $merged passes" }
                return current
            }
        }
    }

    /**
     * Merge overlapping spans, then split internal gaps so unrelated history does not become adjacent.
     * Returns the number of ranges merged.
     *
     * [writtenRangeId] is the range the caller just wrote into. Given one, only it and whatever a merge
     * grew are re-examined for gaps — the rest of the room was already normalised when it was written, and
     * reading every row of every range is the difference between bounded work and the whole room per sync.
     */
    fun normaliseRanges(roomId: String, gapThresholdMs: Long, writtenRangeId: Long? = null): Int {
        val grown = LinkedHashSet<Long>()
        val merged = mergeOverlappingRanges(roomId, grown)
        val toSplit = writtenRangeId?.let { grown + it }
        splitRangesAtGaps(roomId, gapThresholdMs, toSplit)
        return merged
    }

    fun mergeOverlappingRanges(roomId: String, grown: MutableSet<Long>? = null): Int {
        val maxMerges = getByRoom(roomId).size
        var merged = 0
        while (true) {
            val ranges = getByRoom(roomId).filter { it.root_thread_event_id == null }
            val spans = queries.selectChunkTsSpans(roomId).executeAsList()
                    .mapNotNull { row -> row.min_ts?.let { min -> row.max_ts?.let { max -> row.chunk_id to (min to max) } } }
                    .toMap()
            val overlapping = ranges.asSequence()
                    .flatMap { a -> ranges.asSequence().filter { it.id > a.id }.map { a to it } }
                    .firstOrNull { (a, b) ->
                        val spanA = spans[a.id] ?: return@firstOrNull false
                        val spanB = spans[b.id] ?: return@firstOrNull false
                        // Touching at a single timestamp is adjacency, not overlap.
                        spanA.first < spanB.second && spanB.first < spanA.second
                    } ?: return merged
            val (winner, loser) = pickWinner(overlapping.first, overlapping.second)
            DebugLog.w { "CHUNKDBG $roomId merging #${loser.id} (${spans[loser.id]}) into #${winner.id} (${spans[winner.id]}):" +
                            " both describe the same period, so one of them was unreachable" }
            mergeInto(winner.id, loser.id)
            grown?.add(winner.id)
            merged++
            if (merged > maxMerges) {
                DebugLog.e { "CHUNKDBG $roomId gave up merging after $merged passes" }
                return merged
            }
        }
    }

    /**
     * The older side keeps the backward frontier. The newer side has no token for the gap
     * and relies on timestamp healing. Returns the number of ranges split.
     */
    fun splitRangesAtGaps(roomId: String, gapThresholdMs: Long, onlyRangeIds: Set<Long>? = null): Int {
        var split = 0
        val ranges = getByRoom(roomId)
                .filter { it.root_thread_event_id == null && (onlyRangeIds == null || it.id in onlyRangeIds) }
        for (range in ranges) {
            var current: ChunkRow? = range
            while (true) {
                val row = current ?: break
                val rows = database.timelineEventQueries.selectByChunk(row.id).executeAsList()
                // Newest first, so a gap is a drop from one row to the next.
                val cut = rows.zipWithNext().indexOfFirst { (newer, older) ->
                    newer.ts - older.ts > gapThresholdMs && !isGapUnfillable(roomId, older.ts)
                }
                if (cut < 0) break
                val olderSideFrom = rows[cut + 1].ts
                val olderId = insert(
                        roomId, row.prev_token, null,
                        isLastForward = false, isLastBackward = row.is_last_backward != 0L,
                        rootThreadEventId = null, isLastForwardThread = false,
                )
                val moved = rows.drop(cut + 1)
                moved.forEach { database.timelineEventQueries.updateChunkId(olderId, it.id) }
                updatePrevToken(row.id, null)
                if (row.is_last_backward != 0L) setLastBackward(row.id, false)
                DebugLog.w { "CHUNKDBG $roomId split #${row.id} at a ${(rows[cut].ts - olderSideFrom) / 86_400_000}d gap:" +
                                " ${moved.size} event(s) older than $olderSideFrom moved to #$olderId" }
                split++
                // The older side can hold further gaps of its own.
                current = getById(olderId)
            }
        }
        return split
    }

    fun deleteEmptyRanges(roomId: String): Int {
        var dropped = 0
        getByRoom(roomId).forEach { range ->
            if (range.root_thread_event_id != null || range.is_last_forward != 0L) return@forEach
            if (database.timelineEventQueries.countByChunk(range.id).executeAsOne() > 0L) return@forEach
            deleteById(range.id)
            dropped++
        }
        return dropped
    }

    /**
     * Merge two ranges that describe the same history, keeping the one a reader is more likely to be
     * holding: the live range, else the one with more events. @return the surviving range.
     */
    fun mergeKeepingLarger(a: Long, b: Long): Long {
        val rowA = getById(a) ?: return b
        val rowB = getById(b) ?: return a
        val (winner, loser) = pickWinner(rowA, rowB)
        mergeInto(winner.id, loser.id)
        return winner.id
    }

    private fun pickWinner(a: ChunkRow, b: ChunkRow): Pair<ChunkRow, ChunkRow> = when {
        a.is_last_forward != 0L -> a to b
        b.is_last_forward != 0L -> b to a
        database.timelineEventQueries.countByChunk(a.id).executeAsOne() >=
                database.timelineEventQueries.countByChunk(b.id).executeAsOne() -> a to b
        else -> b to a
    }

    private fun spanOf(chunkId: Long): Pair<Long, Long>? {
        val min = database.timelineEventQueries.selectMinTsForChunk(chunkId).executeAsOneOrNull()?.ts ?: return null
        val max = database.timelineEventQueries.selectMaxTsForChunk(chunkId).executeAsOneOrNull()?.ts ?: return null
        return min to max
    }

    fun setLastForward(id: Long, value: Boolean) = queries.updateLastForward(if (value) 1L else 0L, id)

    fun setLastBackward(id: Long, value: Boolean) = queries.updateLastBackward(if (value) 1L else 0L, id)

    fun clearLastBackward(roomId: String) = queries.clearLastBackwardByRoom(roomId)

    fun deleteById(id: Long) = queries.deleteById(id)

    fun deleteByRoom(roomId: String) = queries.deleteByRoom(roomId)

    fun asEntity(row: ChunkRow): ChunkEntity = ChunkEntity(
            prevToken = row.prev_token,
            nextToken = row.next_token,
            isLastForward = row.is_last_forward != 0L,
            isLastBackward = row.is_last_backward != 0L,
            rootThreadEventId = row.root_thread_event_id,
            isLastForwardThread = row.is_last_forward_thread != 0L,
    )

    private companion object {
        // A backstop, not a real bound: each pass deletes a range, so a room cannot need more than it has.
        private const val MAX_MERGE_PASSES = 64
    }
}
