/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.internal.database.model.ChunkEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.Chunk as ChunkRow

/**
 * SQL access for `chunk`. Unmanaged [ChunkEntity] DTOs carry only scalar fields; the chunk's timeline
 * events / state events / prev-next links are navigated via [TimelineEventSqlStore] and the chunk id
 * columns, not the entity RealmLists (which stay empty on the DTO).
 */
internal class ChunkSqlStore(private val database: SessionSqlDatabase) {

    private val queries get() = database.chunkQueries

    fun getById(id: Long): ChunkRow? = queries.selectById(id).executeAsOneOrNull()

    fun getByRoom(roomId: String): List<ChunkRow> = queries.selectByRoom(roomId).executeAsList()

    fun lastForward(roomId: String): ChunkRow? = queries.selectLastForward(roomId).executeAsOneOrNull()

    fun lastForwardThread(roomId: String, rootThreadEventId: String): ChunkRow? =
            queries.selectLastForwardThread(roomId, rootThreadEventId).executeAsOneOrNull()

    fun findByTokens(roomId: String, prevToken: String?, nextToken: String?): ChunkRow? =
            queries.selectByTokens(roomId, prevToken, nextToken).executeAsOneOrNull()

    // A null token means "no boundary" (e.g. the live chunk's next_token, a reached-room-start
    // chunk's prev_token). It must never MATCH another such chunk: `next_token IS NULL` would link a
    // backward page that reached room-start to the live chunk, forming a chunk-graph cycle that traps
    // pagination. Only real tokens identify a shared boundary.
    fun findByNextToken(roomId: String, nextToken: String?): ChunkRow? =
            nextToken?.let { queries.selectByNextToken(roomId, it).executeAsOneOrNull() }

    fun findByPrevToken(roomId: String, prevToken: String?): ChunkRow? =
            prevToken?.let { queries.selectByPrevToken(roomId, it).executeAsOneOrNull() }

    fun lastBackward(roomId: String): ChunkRow? = queries.selectLastBackward(roomId).executeAsOneOrNull()

    fun insert(
            roomId: String,
            prevToken: String?,
            nextToken: String?,
            prevChunkId: Long?,
            nextChunkId: Long?,
            isLastForward: Boolean,
            isLastBackward: Boolean,
            rootThreadEventId: String?,
            isLastForwardThread: Boolean,
    ): Long {
        queries.insert(
                room_id = roomId,
                prev_token = prevToken,
                next_token = nextToken,
                prev_chunk_id = prevChunkId,
                next_chunk_id = nextChunkId,
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

    /** Like [findChunkIdIncludingEvent] but ignores thread-timeline chunks (which duplicate main-timeline events by design). */
    fun findMainChunkIdIncludingEvent(roomId: String, eventId: String): Long? =
            queries.selectMainChunkIdIncludingEvent(roomId, eventId).executeAsOneOrNull()?.chunk_id

    fun findReferencing(roomId: String, chunkId: Long): List<ChunkRow> =
            queries.selectReferencingChunk(roomId, chunkId, chunkId).executeAsList()

    /** The main-timeline chunk whose events' timestamp span strictly contains [ts], preferring the largest. */
    fun findChunkCoveringTs(roomId: String, excludeChunkId: Long, ts: Long): Long? =
            queries.selectChunkCoveringTs(roomId, excludeChunkId, ts).executeAsOneOrNull()?.chunk_id

    /**
     * Delete [island] and hand its graph position to [absorberId], which now covers its region.
     * Referencing chunks are re-pointed at the absorber, with the same self-link/2-cycle guards as
     * pagination linking; where linking would cycle, the reference is cleared rather than left
     * dangling on the dead chunk. The island's timeline rows are the caller's responsibility.
     */
    fun retireChunkInto(roomId: String, island: ChunkRow, absorberId: Long) {
        deleteStateEventsForChunk(island.id)
        deleteById(island.id)
        val absorber = getById(absorberId) ?: return
        for (ref in findReferencing(roomId, island.id)) {
            if (ref.id == absorberId) {
                if (ref.prev_chunk_id == island.id) updatePrevChunkId(absorberId, island.prev_chunk_id?.takeIf { it != absorberId })
                if (ref.next_chunk_id == island.id) updateNextChunkId(absorberId, island.next_chunk_id?.takeIf { it != absorberId })
                continue
            }
            if (ref.prev_chunk_id == island.id) {
                updatePrevChunkId(ref.id, absorberId.takeIf { absorber.prev_chunk_id != ref.id })
            }
            if (ref.next_chunk_id == island.id) {
                updateNextChunkId(ref.id, absorberId.takeIf { absorber.next_chunk_id != ref.id })
            }
        }
    }

    fun updateLinks(id: Long, prevChunkId: Long?, nextChunkId: Long?) = queries.updateLinks(prevChunkId, nextChunkId, id)

    fun updatePrevChunkId(id: Long, prevChunkId: Long?) = queries.updatePrevChunkId(prevChunkId, id)

    fun updateNextChunkId(id: Long, nextChunkId: Long?) = queries.updateNextChunkId(nextChunkId, id)

    fun setLastForward(id: Long, value: Boolean) = queries.updateLastForward(if (value) 1L else 0L, id)

    fun setLastBackward(id: Long, value: Boolean) = queries.updateLastBackward(if (value) 1L else 0L, id)

    fun clearLastBackward(roomId: String) = queries.clearLastBackwardByRoom(roomId)

    fun deleteById(id: Long) = queries.deleteById(id)

    fun deleteByRoom(roomId: String) = queries.deleteByRoom(roomId)

    fun stateEventDbIds(chunkId: Long): List<Long> = queries.selectStateEventDbIds(chunkId).executeAsList()

    fun addStateEvent(chunkId: Long, eventDbId: Long) = queries.insertStateEvent(chunkId, eventDbId)

    fun deleteStateEventsForChunk(chunkId: Long) = queries.deleteStateEventsForChunk(chunkId)

    fun asEntity(row: ChunkRow): ChunkEntity = ChunkEntity(
            prevToken = row.prev_token,
            nextToken = row.next_token,
            isLastForward = row.is_last_forward != 0L,
            isLastBackward = row.is_last_backward != 0L,
            rootThreadEventId = row.root_thread_event_id,
            isLastForwardThread = row.is_last_forward_thread != 0L,
    )
}
