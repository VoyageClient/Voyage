/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.internal.session.room.timeline.PaginationDirection
import timber.log.Timber

/**
 * Puts events the homeserver delivered out of chronological order back at their timestamp position.
 * A server that was down or is federating slowly hands us a day-old message at the live edge, or a
 * scrambled page during back-pagination, and it renders under a later date's messages.
 *
 * An event goes into its own chunk when that chunk's timestamp span contains it, otherwise into the
 * chunk that does span it. One whose region isn't cached yet stays put until the room gains that
 * history ([retryUnplaced]). [sweepRoom] repairs what earlier builds stored out of order, and picks
 * up parked events again after a restart.
 *
 * All entry points must be called inside a session-database transaction.
 */
internal class TimelineOrderRepairer(private val stores: SessionStores) {

    // Events waiting for the region they belong to, per room: event id -> origin_server_ts.
    private val unplaced = HashMap<String, LinkedHashMap<String, Long>>()

    /** Reposition a row just inserted at the [direction] end of [chunkId], if it belongs further in. */
    fun placeInserted(roomId: String, chunkId: Long, rowId: Long, eventId: String, ts: Long, direction: PaginationDirection) {
        if (stores.chunk.getById(chunkId)?.root_thread_event_id != null) return
        val displaced = when (direction) {
            PaginationDirection.FORWARDS -> stores.timelineEvent.tsAtNewestRow(chunkId, rowId)?.let { ts < it - THRESHOLD_MS }
            PaginationDirection.BACKWARDS -> stores.timelineEvent.tsAtOldestRow(chunkId, rowId)?.let { ts > it + THRESHOLD_MS }
        }
        if (displaced != true) return
        if (!place(roomId, chunkId, rowId, ts, currentIndex = null)) {
            park(roomId, eventId, ts)
        }
    }

    /** Retry events parked for want of the region they belong to, which the room may hold by now. */
    fun retryUnplaced(roomId: String) {
        val pending = unplaced[roomId] ?: return
        for ((eventId, ts) in pending.entries.toList()) {
            val placement = stores.timelineEvent.getPlacement(roomId, eventId)
            if (placement == null) {
                pending.remove(eventId)
                continue
            }
            if (place(roomId, placement.chunkId, placement.id, ts, placement.displayIndex)) {
                pending.remove(eventId)
                Timber.i("TimelineOrderRepairer $roomId: placed parked $eventId once its region loaded")
            }
        }
        if (pending.isEmpty()) unplaced.remove(roomId)
    }

    /**
     * Whole-room pass over stored order. A row whose timestamp falls more than [THRESHOLD_MS] behind a
     * row above it in the same chunk is the displaced one, and gets re-placed.
     */
    fun sweepRoom(roomId: String) {
        for (chunk in stores.chunk.getByRoom(roomId)) {
            if (chunk.root_thread_event_id != null) continue
            var newestSoFar: Long? = null
            val displaced = ArrayList<TimelineEventSqlStore.ChunkRowTs>()
            for (row in stores.timelineEvent.getChunkRowsWithTs(chunk.id)) {
                if (newestSoFar != null && row.ts < newestSoFar - THRESHOLD_MS) {
                    displaced.add(row)
                } else {
                    newestSoFar = maxOf(newestSoFar ?: row.ts, row.ts)
                }
            }
            for (row in displaced) {
                if (!place(roomId, chunk.id, row.id, row.ts, row.displayIndex)) {
                    park(roomId, row.eventId, row.ts)
                }
            }
            if (displaced.isNotEmpty()) {
                Timber.i("TimelineOrderRepairer $roomId: re-placed ${displaced.size} out-of-order rows of chunk ${chunk.id}")
            }
        }
        retryUnplaced(roomId)
    }

    /** Splice the row into the chunk that spans [ts], its own included. False when no chunk does. */
    private fun place(roomId: String, chunkId: Long, rowId: Long, ts: Long, currentIndex: Long?): Boolean {
        val oldestTs = stores.timelineEvent.tsAtOldestRow(chunkId, rowId)
        val newestTs = stores.timelineEvent.tsAtNewestRow(chunkId, rowId)
        if (oldestTs != null && newestTs != null && ts >= oldestTs && ts <= newestTs) {
            val predecessor = stores.timelineEvent.maxDisplayIndexAtOrBeforeTsExcluding(chunkId, ts, rowId) ?: return false
            spliceAfter(chunkId, rowId, predecessor, currentIndex)
            return true
        }
        val targetChunkId = stores.chunk.findChunkCoveringTs(roomId, chunkId, ts) ?: return false
        val predecessor = stores.timelineEvent.maxDisplayIndexAtOrBeforeTs(targetChunkId, ts) ?: return false
        spliceAfter(targetChunkId, rowId, predecessor, currentIndex = null)
        return true
    }

    private fun spliceAfter(chunkId: Long, rowId: Long, predecessorIndex: Long, currentIndex: Long?) {
        if (currentIndex == predecessorIndex + 1) return
        stores.timelineEvent.shiftDisplayIndicesUpAfter(chunkId, predecessorIndex)
        stores.timelineEvent.moveToChunkAtIndex(rowId, chunkId, predecessorIndex + 1)
    }

    private fun park(roomId: String, eventId: String, ts: Long) {
        val pending = unplaced.getOrPut(roomId) { LinkedHashMap() }
        pending[eventId] = ts
        while (pending.size > MAX_PARKED_PER_ROOM) {
            pending.remove(pending.keys.first())
        }
    }

    companion object {
        // Below this, a "late" event is homeserver clock skew rather than delayed delivery, and moving it
        // would shuffle messages that are in the order they were actually sent.
        private const val THRESHOLD_MS = 15 * 60 * 1000L
        private const val MAX_PARKED_PER_ROOM = 256
    }
}
