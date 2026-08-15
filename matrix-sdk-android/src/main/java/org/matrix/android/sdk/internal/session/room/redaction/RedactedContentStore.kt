/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.redaction

import org.matrix.android.sdk.internal.session.SessionReleasable
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.internal.database.sqldelight.SqlDriverFactory
import org.matrix.android.sdk.internal.di.SessionFilesDirectory
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.room.redaction.db.RedactedContentSqlDatabase
import org.matrix.android.sdk.internal.session.room.redaction.db.Redacted_content
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject

/** How a preserved copy was obtained. Persisted as [PreservedContent.source]. */
internal enum class PreservationSource(val value: Long) {
    /** Fetched after the fact via MSC2815. */
    MSC2815(0L),

    /** Written locally rather than fetched: the tombstone for a message redacted mid-upload. */
    CAPTURED(1L);

    companion object {
        fun fromValue(value: Long) = entries.firstOrNull { it.value == value } ?: MSC2815
    }
}

internal data class PreservedContent(
        val eventId: String,
        val roomId: String,
        val content: String,
        val clearType: String?,
        val sender: String?,
        val originServerTs: Long?,
        val source: PreservationSource,
        val fetchedAt: Long,
)

/**
 * Pre-redaction event content, in its own database file inside the session directory.
 *
 * Deliberately not part of the session store: that one is dropped wholesale on any schema version
 * bump (FrameworkSqliteDriver has no migrations) and wiped by clear-cache, either of which would
 * silently discard content the user asked to keep indefinitely. Living in the session directory
 * still means logout deletes it.
 */
@SessionScope
internal class RedactedContentStore @Inject constructor(
        @SessionFilesDirectory private val directory: File,
        private val driverFactory: SqlDriverFactory,
) : SessionReleasable {

    // Like the session database, the driver and its thread live as long as the session component:
    // a stopped session may be reopened, so teardown happens on component release only.
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "redacted_content_db")
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    @Volatile
    private var driver: SqlDriver? = null

    private val database by lazy {
        RedactedContentSqlDatabase(
                driverFactory.create(RedactedContentSqlDatabase.Schema, File(directory, "redacted_content.db")).also { driver = it }
        )
    }

    override fun onSessionReleased() {
        // Serialized behind any in-flight queries; the dedicated thread then exits.
        executor.execute { runCatching { driver?.close() } }
        executor.shutdown()
    }

    private val queries get() = database.redactedContentQueries

    suspend fun put(content: PreservedContent) = withContext(dispatcher) {
        queries.upsert(
                event_id = content.eventId,
                room_id = content.roomId,
                content = content.content,
                clear_type = content.clearType,
                sender = content.sender,
                origin_server_ts = content.originServerTs,
                source = content.source.value,
                fetched_at = content.fetchedAt,
        )
    }

    suspend fun get(eventId: String): PreservedContent? = withContext(dispatcher) {
        queries.selectByEventId(eventId).executeAsOneOrNull()?.toDomain()
    }

    private fun Redacted_content.toDomain() = PreservedContent(
            eventId = event_id,
            roomId = room_id,
            content = content,
            clearType = clear_type,
            sender = sender,
            originServerTs = origin_server_ts,
            source = PreservationSource.fromValue(source),
            fetchedAt = fetched_at,
    )

    suspend fun getForRoom(roomId: String): List<PreservedContent> = withContext(dispatcher) {
        queries.selectByRoom(roomId).executeAsList().map { it.toDomain() }
    }

    suspend fun getContaining(roomId: String, needle: String): List<PreservedContent> = withContext(dispatcher) {
        queries.selectByRoomContaining(roomId, needle).executeAsList().map { it.toDomain() }
    }

    /** The ids a [clearExcept] would drop. */
    suspend fun eventIdsOutsideRooms(roomIds: Collection<String>): List<String> = withContext(dispatcher) {
        if (roomIds.isEmpty()) {
            queries.selectAllEventIds().executeAsList()
        } else {
            queries.selectEventIdsNotInRooms(roomIds).executeAsList()
        }
    }

    suspend fun roomsWithContent(): List<String> = withContext(dispatcher) {
        queries.distinctRooms().executeAsList()
    }

    /** Clear-cache: wipe everything except rooms the user opted out of clearing. */
    suspend fun clearExcept(roomIds: Collection<String>) = withContext(dispatcher) {
        if (roomIds.isEmpty()) {
            queries.deleteAllRooms()
        } else {
            queries.deleteRoomsNotIn(roomIds)
        }
    }
}
