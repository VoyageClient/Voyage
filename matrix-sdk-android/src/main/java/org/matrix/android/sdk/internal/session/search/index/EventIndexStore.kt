/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.search.index

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.internal.database.sqldelight.SqlDriverFactory
import org.matrix.android.sdk.internal.di.SessionFilesDirectory
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.search.index.db.EventIndexSqlDatabase
import org.matrix.android.sdk.internal.session.search.index.db.Indexed_event
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject

internal data class IndexableEvent(
        val eventId: String,
        val roomId: String,
        val sender: String?,
        val originServerTs: Long,
        val contentText: String,
        val eventJson: String,
        val msgtype: String?,
        val mentions: String?,
)

internal data class IndexCheckpoint(
        val roomId: String,
        val token: String,
        val backwards: Boolean,
        val fullCrawl: Boolean,
)

/**
 * Storage for the local message search index, in its own database file inside the session
 * directory (so it is deleted with the session and survives session-schema drops).
 */
@SessionScope
internal class EventIndexStore @Inject constructor(
        @SessionFilesDirectory private val directory: File,
        private val driverFactory: SqlDriverFactory,
) {

    // Like the session database, the driver and its thread live as long as the session component:
    // a stopped session may be reopened, so there is no teardown hook to close them on.
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "event_index_db")
    }.asCoroutineDispatcher()

    private val database by lazy {
        EventIndexSqlDatabase(
                driverFactory.create(EventIndexSqlDatabase.Schema, File(directory, "event_index.db"))
        )
    }

    private val queries get() = database.eventIndexQueries

    /** @return how many of [events] were new to the index. */
    suspend fun addEvents(events: List<IndexableEvent>): Int = withContext(dispatcher) {
        var added = 0
        queries.transaction {
            events.forEach { event ->
                val exists = queries.eventExists(event.eventId).executeAsOneOrNull() != null
                if (!exists) {
                    queries.insertEvent(
                            event.eventId,
                            event.roomId,
                            event.sender,
                            event.originServerTs,
                            event.contentText,
                            event.eventJson,
                            event.msgtype,
                            event.mentions,
                    )
                    added++
                }
            }
        }
        added
    }

    suspend fun deleteEvent(eventId: String) = withContext(dispatcher) {
        queries.deleteEvent(eventId)
    }

    suspend fun deleteLocalEchoes() = withContext(dispatcher) {
        queries.deleteLocalEchoes("\$local.%")
    }

    suspend fun search(roomId: String, searchTerm: String, limit: Int, offset: Int): List<Indexed_event> =
            withContext(dispatcher) {
                queries.search(roomId, likePattern(searchTerm), limit.toLong(), offset.toLong()).executeAsList()
            }

    suspend fun oldestTsInRoom(roomId: String): Long? = withContext(dispatcher) {
        queries.oldestTsInRoom(roomId).executeAsOneOrNull()
    }

    suspend fun isRoomIndexed(roomId: String): Boolean = withContext(dispatcher) {
        queries.isRoomIndexed(roomId).executeAsOneOrNull() != null
    }

    suspend fun getStats(): Pair<Long, Long> = withContext(dispatcher) {
        queries.countEvents().executeAsOne() to queries.countRooms().executeAsOne()
    }

    suspend fun loadCheckpoints(): List<IndexCheckpoint> = withContext(dispatcher) {
        queries.selectCheckpoints().executeAsList().map {
            IndexCheckpoint(
                    roomId = it.room_id,
                    token = it.token,
                    backwards = it.direction == DIRECTION_BACKWARDS,
                    fullCrawl = it.full_crawl != 0L,
            )
        }
    }

    suspend fun addCheckpoint(checkpoint: IndexCheckpoint) = withContext(dispatcher) {
        queries.insertCheckpoint(
                checkpoint.roomId,
                checkpoint.token,
                if (checkpoint.backwards) DIRECTION_BACKWARDS else DIRECTION_FORWARDS,
                if (checkpoint.fullCrawl) 1L else 0L,
        )
    }

    suspend fun removeCheckpoint(checkpoint: IndexCheckpoint) = withContext(dispatcher) {
        queries.deleteCheckpoint(
                checkpoint.roomId,
                checkpoint.token,
                if (checkpoint.backwards) DIRECTION_BACKWARDS else DIRECTION_FORWARDS,
        )
    }

    suspend fun getCrawledToken(roomId: String): String? = withContext(dispatcher) {
        queries.selectCrawledToken(roomId).executeAsOneOrNull()
    }

    /** Set once a backward crawl reached the very start of the room's history. */
    suspend fun isRoomFullyCrawled(roomId: String): Boolean = withContext(dispatcher) {
        queries.selectMeta(KEY_FULLY_CRAWLED_PREFIX + roomId).executeAsOneOrNull() != null
    }

    suspend fun markRoomFullyCrawled(roomId: String) = withContext(dispatcher) {
        queries.upsertMeta(KEY_FULLY_CRAWLED_PREFIX + roomId, "1")
    }

    suspend fun setCrawledToken(roomId: String, token: String) = withContext(dispatcher) {
        queries.upsertCrawledToken(roomId, token)
    }

    suspend fun getSweepWatermark(): Long = withContext(dispatcher) {
        queries.selectMeta(KEY_SWEEP_WATERMARK).executeAsOneOrNull()?.toLongOrNull() ?: 0L
    }

    suspend fun setSweepWatermark(watermark: Long) = withContext(dispatcher) {
        queries.upsertMeta(KEY_SWEEP_WATERMARK, watermark.toString())
    }

    suspend fun clear() = withContext(dispatcher) {
        queries.transaction {
            queries.clearEvents()
            queries.clearCheckpoints()
            queries.clearCrawledRooms()
            queries.clearMeta()
        }
    }

    companion object {
        private const val DIRECTION_BACKWARDS = "b"
        private const val DIRECTION_FORWARDS = "f"
        private const val KEY_SWEEP_WATERMARK = "sweep_watermark"
        private const val KEY_FULLY_CRAWLED_PREFIX = "fully_crawled:"

        /** LIKE pattern for a case-insensitive substring match; terms are stored lowercased. */
        fun likePattern(term: String): String {
            val escaped = term.lowercase()
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_")
            return "%$escaped%"
        }
    }
}
