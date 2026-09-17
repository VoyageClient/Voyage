/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.pushers

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.core.extensions.useCompat
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

enum class PushRequestStatus(val value: Int) {
    /** Never fetched, or fetched and failed in a way worth retrying. */
    PENDING(0),
    SUCCESS(1),

    /** Failed for a reason retrying cannot fix. */
    FAILED(2);

    companion object {
        fun fromValue(value: Int) = entries.firstOrNull { it.value == value } ?: PENDING
    }
}

data class PushRequest(
        val eventId: String,
        val roomId: String,
        val sessionId: String,
        val pushDate: Long,
        val providerInfo: String,
        val status: PushRequestStatus = PushRequestStatus.PENDING,
        val retries: Int = 0,
        val failureReason: String? = null,
)

/**
 * Every push is written down before it is acted on, so a fetch that fails (no network, process
 * killed mid-flight) can be retried instead of the notification being lost for good. It doubles as
 * the push history shown in the notification troubleshoot screen.
 */
@Singleton
class PushRequestStore @Inject constructor(
        @ApplicationContext context: Context,
) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
                "CREATE TABLE $TABLE (" +
                        "$COL_EVENT_ID TEXT NOT NULL, " +
                        "$COL_ROOM_ID TEXT NOT NULL, " +
                        "$COL_SESSION_ID TEXT NOT NULL, " +
                        "$COL_PUSH_DATE INTEGER NOT NULL, " +
                        "$COL_PROVIDER TEXT NOT NULL, " +
                        "$COL_STATUS INTEGER NOT NULL, " +
                        "$COL_RETRIES INTEGER NOT NULL, " +
                        "$COL_REASON TEXT, " +
                        "PRIMARY KEY ($COL_SESSION_ID, $COL_EVENT_ID))"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun insertOrUpdate(request: PushRequest) {
        tryWrite {
            it.insertWithOnConflict(TABLE, null, request.toContentValues(), SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun insertOrUpdate(requests: List<PushRequest>) {
        tryWrite { db ->
            db.beginTransaction()
            try {
                requests.forEach {
                    db.insertWithOnConflict(TABLE, null, it.toContentValues(), SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun getPending(sessionId: String, since: Long): List<PushRequest> {
        return query(
                selection = "$COL_SESSION_ID = ? AND $COL_STATUS = ? AND $COL_PUSH_DATE >= ?",
                selectionArgs = arrayOf(sessionId, PushRequestStatus.PENDING.value.toString(), since.toString()),
                limit = null,
        )
    }

    /** Most recent first, for the push history screen. */
    fun getRecent(limit: Int): List<PushRequest> {
        return query(selection = null, selectionArgs = null, limit = limit.toString())
    }

    fun removeOlderThan(timestamp: Long) {
        tryWrite {
            it.delete(TABLE, "$COL_PUSH_DATE < ?", arrayOf(timestamp.toString()))
        }
    }

    fun clear() {
        tryWrite { it.delete(TABLE, null, null) }
    }

    private fun query(selection: String?, selectionArgs: Array<String>?, limit: String?): List<PushRequest> {
        return try {
            readableDatabase.query(TABLE, null, selection, selectionArgs, null, null, "$COL_PUSH_DATE DESC", limit)
                    .useCompat { cursor ->
                        val result = ArrayList<PushRequest>(cursor.count)
                        while (cursor.moveToNext()) {
                            result.add(
                                    PushRequest(
                                            eventId = cursor.getString(cursor.getColumnIndexOrThrow(COL_EVENT_ID)),
                                            roomId = cursor.getString(cursor.getColumnIndexOrThrow(COL_ROOM_ID)),
                                            sessionId = cursor.getString(cursor.getColumnIndexOrThrow(COL_SESSION_ID)),
                                            pushDate = cursor.getLong(cursor.getColumnIndexOrThrow(COL_PUSH_DATE)),
                                            providerInfo = cursor.getString(cursor.getColumnIndexOrThrow(COL_PROVIDER)),
                                            status = PushRequestStatus.fromValue(cursor.getInt(cursor.getColumnIndexOrThrow(COL_STATUS))),
                                            retries = cursor.getInt(cursor.getColumnIndexOrThrow(COL_RETRIES)),
                                            failureReason = cursor.getString(cursor.getColumnIndexOrThrow(COL_REASON)),
                                    )
                            )
                        }
                        result
                    }
        } catch (throwable: Throwable) {
            Timber.e(throwable, "PushRequestStore: query failed")
            emptyList()
        }
    }

    private inline fun tryWrite(block: (SQLiteDatabase) -> Unit) {
        try {
            block(writableDatabase)
        } catch (throwable: Throwable) {
            Timber.e(throwable, "PushRequestStore: write failed")
        }
    }

    private fun PushRequest.toContentValues() = ContentValues().apply {
        put(COL_EVENT_ID, eventId)
        put(COL_ROOM_ID, roomId)
        put(COL_SESSION_ID, sessionId)
        put(COL_PUSH_DATE, pushDate)
        put(COL_PROVIDER, providerInfo)
        put(COL_STATUS, status.value)
        put(COL_RETRIES, retries)
        put(COL_REASON, failureReason)
    }

    companion object {
        private const val DB_NAME = "push_requests.db"
        private const val DB_VERSION = 1
        private const val TABLE = "push_request"
        private const val COL_EVENT_ID = "event_id"
        private const val COL_ROOM_ID = "room_id"
        private const val COL_SESSION_ID = "session_id"
        private const val COL_PUSH_DATE = "push_date"
        private const val COL_PROVIDER = "provider_info"
        private const val COL_STATUS = "status"
        private const val COL_RETRIES = "retries"
        private const val COL_REASON = "failure_reason"
    }
}
