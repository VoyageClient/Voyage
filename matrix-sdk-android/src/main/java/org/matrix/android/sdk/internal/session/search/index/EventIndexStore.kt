/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.search.index

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.internal.database.sqldelight.SqlDriverFactory
import org.matrix.android.sdk.internal.di.SessionFilesDirectory
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.SessionReleasable
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.search.ROOM_MENTION_SENTINEL
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

/** The slice of an indexed row a cross-room reader needs; the stored clear event carries the rest. */
internal data class IndexedRow(
        val roomId: String,
        val sender: String?,
        val eventJson: String,
)

private const val MENTION_BACKFILL_KEY = "mention_hits_backfilled"
private const val MENTION_KIND_USER = "USER"
private const val MENTION_KIND_ROOM = "ROOM"

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
        @UserId private val userId: String,
        private val driverFactory: SqlDriverFactory,
) : SessionReleasable {

    private val ownMention = userId.lowercase()

    // Like the session database, the driver and its thread live as long as the session component:
    // a stopped session may be reopened, so teardown happens on component release only.
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "event_index_db")
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    @Volatile
    private var driver: SqlDriver? = null

    private val database by lazy {
        val opened = driverFactory.create(EventIndexSqlDatabase.Schema, File(directory, "event_index.db")).also { driver = it }
        // Added after the schema shipped: a version bump would drop the whole crawled index, so these
        // are applied idempotently instead (see SessionModule for the same pattern). Building the
        // index over an already-crawled table costs real time, but only on the first open.
        opened.execute(null, "CREATE INDEX IF NOT EXISTS indexed_event_mentions ON indexed_event(mentions)", 0)
        opened.execute(null, """
            CREATE TABLE IF NOT EXISTS mention_hit (
                event_id TEXT NOT NULL PRIMARY KEY,
                room_id TEXT NOT NULL,
                origin_server_ts INTEGER NOT NULL,
                kind TEXT NOT NULL
            )
        """.trimIndent(), 0)
        opened.execute(null, "CREATE INDEX IF NOT EXISTS mention_hit_ts ON mention_hit(origin_server_ts)", 0)
        EventIndexSqlDatabase(opened)
    }

    override fun onSessionReleased() {
        // Serialized behind any in-flight queries; the dedicated thread then exits.
        executor.execute { runCatching { driver?.close() } }
        executor.shutdown()
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
                    recordMentionHit(event)
                    added++
                }
            }
        }
        added
    }

    /** Replaces the row if it is already there, unlike [addEvents] which only fills gaps. */
    suspend fun putEvent(event: IndexableEvent) = withContext(dispatcher) {
        queries.upsertEvent(
                event.eventId,
                event.roomId,
                event.sender,
                event.originServerTs,
                event.contentText,
                event.eventJson,
                event.msgtype,
                event.mentions,
        )
        recordMentionHit(event)
    }

    /** Mirrors an indexed event into [mention_hit], or out of it once it no longer mentions us. */
    private fun recordMentionHit(event: IndexableEvent) {
        val kind = mentionKindOf(event.mentions)
        if (kind == null) {
            queries.deleteMentionHit(event.eventId)
        } else {
            queries.insertMentionHit(event.eventId, event.roomId, event.originServerTs, kind)
        }
    }

    /** The column is a space-joined list of lowercased ids, plus `@room` for an `m.mentions.room` event. */
    private fun mentionKindOf(mentions: String?): String? {
        val parts = mentions?.split(' ') ?: return null
        return when {
            ownMention in parts -> MENTION_KIND_USER
            ROOM_MENTION_SENTINEL in parts -> MENTION_KIND_ROOM
            else -> null
        }
    }

    suspend fun eventJson(eventId: String): String? = withContext(dispatcher) {
        queries.eventJson(eventId).executeAsOneOrNull()
    }

    suspend fun updateEventJson(eventId: String, json: String) = withContext(dispatcher) {
        queries.updateEventJson(json, eventId)
    }

    /** Keeps the row, drops everything the (now redacted) content contributed to it. */
    suspend fun stripEvent(eventId: String, json: String) = withContext(dispatcher) {
        queries.stripEvent(json, eventId)
        queries.deleteMentionHit(eventId)
    }

    suspend fun deleteEvent(eventId: String) = withContext(dispatcher) {
        queries.deleteEvent(eventId)
        queries.deleteMentionHit(eventId)
    }

    suspend fun deleteLocalEchoes() = withContext(dispatcher) {
        queries.deleteLocalEchoes("\$local.%")
    }

    suspend fun search(roomId: String, searchTerm: String, limit: Int, offset: Int): List<Indexed_event> =
            withContext(dispatcher) {
                queries.search(roomId, likePattern(searchTerm), limit.toLong(), offset.toLong()).executeAsList()
            }

    /** Everything indexed that mentions us, by our id or by `@room`, newest first. */
    suspend fun mentionHits(limit: Int): List<IndexedRow> = withContext(dispatcher) {
        backfillMentionHits()
        queries.selectMentionHits(limit.toLong()).executeAsList()
                .map { IndexedRow(roomId = it.room_id, sender = it.sender, eventJson = it.event_json) }
    }

    /**
     * Fill [mention_hit] from events indexed before it existed. One scan of the whole mentions range,
     * once per session database, rather than on every open of the screen.
     */
    private fun backfillMentionHits() {
        if (queries.selectMeta(MENTION_BACKFILL_KEY).executeAsOneOrNull() != null) return
        queries.transaction {
            // ROOM first so an event that is both is left as USER.
            listOf(ROOM_MENTION_SENTINEL to MENTION_KIND_ROOM, ownMention to MENTION_KIND_USER).forEach { (needle, kind) ->
                queries.selectMentionBackfill(likePattern(needle)).executeAsList().forEach { row ->
                    queries.insertMentionHit(row.event_id, row.room_id, row.origin_server_ts, kind)
                }
            }
            queries.upsertMeta(MENTION_BACKFILL_KEY, "1")
        }
    }

    /** Rows whose searchable text holds [pattern], across every room. */
    suspend fun byContentLike(pattern: String, limit: Int): List<IndexedRow> = withContext(dispatcher) {
        queries.selectByContentLike(pattern, limit.toLong()).executeAsList()
                .map { IndexedRow(roomId = it.room_id, sender = it.sender, eventJson = it.event_json) }
    }

    /** Indexed events with origin_server_ts strictly inside (olderTs, newerTs), newest first. */
    suspend fun eventsInTsRange(roomId: String, olderTs: Long, newerTs: Long, limit: Int, newestFirst: Boolean = true): List<Pair<String, Long>> =
            withContext(dispatcher) {
                if (newestFirst) {
                    queries.eventsInTsRangeNewestFirst(roomId, olderTs, newerTs, limit.toLong()).executeAsList()
                            .map { it.event_id to it.origin_server_ts }
                } else {
                    queries.eventsInTsRangeOldestFirst(roomId, olderTs, newerTs, limit.toLong()).executeAsList()
                            .map { it.event_id to it.origin_server_ts }
                }
            }

    /** Indexed events inside (olderTs, newerTs), closest to [targetTs] first. */
    suspend fun eventsNearestTsInRange(roomId: String, olderTs: Long, newerTs: Long, targetTs: Long, limit: Int): List<Pair<String, Long>> =
            withContext(dispatcher) {
                queries.eventsNearestTsInRange(roomId, olderTs, newerTs, targetTs, limit.toLong()).executeAsList()
                        .map { it.event_id to it.origin_server_ts }
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

    suspend fun getFormatVersion(): Int = withContext(dispatcher) {
        queries.selectMeta(KEY_FORMAT_VERSION).executeAsOneOrNull()?.toIntOrNull() ?: 0
    }

    suspend fun setFormatVersion(version: Int) = withContext(dispatcher) {
        queries.upsertMeta(KEY_FORMAT_VERSION, version.toString())
    }

    suspend fun clear() = withContext(dispatcher) {
        queries.transaction {
            queries.clearEvents()
            queries.clearMentionHits()
            queries.clearCheckpoints()
            queries.clearCrawledRooms()
            queries.clearMeta()
        }
    }

    companion object {
        private const val DIRECTION_BACKWARDS = "b"
        private const val DIRECTION_FORWARDS = "f"
        private const val KEY_SWEEP_WATERMARK = "sweep_watermark"
        private const val KEY_FORMAT_VERSION = "format_version"
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
