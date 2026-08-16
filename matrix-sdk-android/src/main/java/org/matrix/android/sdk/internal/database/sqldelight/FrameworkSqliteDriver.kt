/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sqldelight

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteCursorDriver
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteProgram
import android.database.sqlite.SQLiteQuery
import android.database.sqlite.SQLiteStatement
import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.db.SqlSchema
import org.matrix.android.sdk.api.util.MatrixPerf
import org.matrix.android.sdk.internal.util.use
import java.io.File

/**
 * A SQLDelight [SqlDriver] backed directly by the framework `android.database.sqlite` stack —
 * deliberately without the `androidx.sqlite` wrapper, whose minSdk would otherwise become the floor.
 * The framework SQLite APIs used here exist since API 1 except [SQLiteDatabase.compileStatement]'s
 * `executeUpdateDelete` (API 11), which is well below the app's AndroidX floor.
 *
 * Typed parameters (notably BLOBs for the crypto store) are bound through a cursor factory rather
 * than `rawQuery`'s string-only `selectionArgs`.
 */
internal class FrameworkSqliteDriver private constructor(
        private val openHelper: SQLiteOpenHelper?,
        private val suppliedDatabase: SQLiteDatabase?,
        private val closeSuppliedDatabase: Boolean,
) : SqlDriver {

    /** Normal use: own the database lifecycle through an open helper. */
    constructor(openHelper: SQLiteOpenHelper) : this(openHelper, null, false)

    /** Transient use during onCreate/onUpgrade, bound to the in-progress database (not owned). */
    private constructor(database: SQLiteDatabase) : this(null, database, false)

    private val database: SQLiteDatabase by lazy {
        (suppliedDatabase ?: openHelper!!.writableDatabase).also {
            // Enlarge the framework's internal compiled-SQL cache used by rawQuery() (reads).
            runCatching { it.setMaxSqlCacheSize(SQLiteDatabase.MAX_SQL_CACHE_SIZE) }
            // WAL + the framework's read connection pool: readers no longer block while a write
            // transaction is open. In rollback-journal mode every sync transaction (50-300ms) stalled
            // every concurrent read — including any on the main thread. No-ops safely where
            // unsupported (in-memory DBs return false).
            runCatching { it.enableWriteAheadLogging() }
        }
    }

    @Volatile
    private var closed = false

    // A released session's flows can still be mid-collection when its database is closed on account
    // switch, and the framework throws on a closed handle. Cancellation unwinds those collectors quietly.
    private fun checkOpen() {
        if (closed) throw SessionDatabaseClosedException()
    }

    private val transactions = ThreadLocal<Transaction?>()

    // Serialises every top-level transaction so the several session DB threads never open two at once.
    private val transactionLock = java.util.concurrent.locks.ReentrantLock()
    private val listeners = linkedMapOf<String, MutableSet<Query.Listener>>()

    // SQLDelight hands each prepared statement a stable [identifier]; caching the compiled
    // SQLiteStatement by it avoids recompiling the SQL on every write (huge for bulk inserts like key
    // import / sync — compileStatement() bypasses the framework's own SQL cache). The cache is
    // thread-local: writes run on the DB's single-thread dispatcher, so this needs no locking and — unlike
    // a shared cache guarded per-statement — can't deadlock against a statement's internal DB lock.
    private val statementCache = object : ThreadLocal<HashMap<Int, SQLiteStatement>>() {
        override fun initialValue() = HashMap<Int, SQLiteStatement>()
    }

    override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        checkOpen()
        val perfStart = MatrixPerf.now()
        try {
            // No identifier (e.g. schema DDL) → not reusable, compile once and discard.
            if (identifier == null) {
                val statement = database.compileStatement(sql)
                try {
                    if (binders != null) FrameworkProgramBinder(statement).binders()
                    return QueryResult.Value(statement.executeUpdateDelete().toLong())
                } finally {
                    statement.close()
                }
            }
            val statement = statementCache.get()!!.getOrPut(identifier) { database.compileStatement(sql) }
            statement.clearBindings()
            if (binders != null) FrameworkProgramBinder(statement).binders()
            return QueryResult.Value(statement.executeUpdateDelete().toLong())
        } finally {
            MatrixPerf.end(perfStart) { "db.write${mainThreadFlag()} [${sql.perfSnippet()}]" }
        }
    }

    override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        checkOpen()
        val perfStart = MatrixPerf.now()
        try {
            val cursor: Cursor = if (binders == null) {
                database.rawQuery(sql, null)
            } else {
                // editTable is only used by the deprecated cursor write-back path, which SQLDelight
                // never exercises (cursors are read forward then closed), so a placeholder is safe.
                database.rawQueryWithFactory(BindingCursorFactory(binders), sql, null, "")
            }
            return cursor.use { mapper(FrameworkCursor(it)) }
        } finally {
            MatrixPerf.end(perfStart) { "db.query${mainThreadFlag()} [${sql.perfSnippet()}]" }
        }
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        checkOpen()
        val enclosing = transactions.get()
        if (enclosing == null) {
            // Android's beginTransaction always takes SQLite's single writer lock — even for a
            // read-only SQLDelight transaction. This database is touched from several session
            // threads (write, read, timeline), so without serialising here a transaction on one
            // races the writer on another and one side gets SQLITE_BUSY. Hold a process-wide lock
            // for the whole transaction so only one is ever open on this database at a time.
            transactionLock.lock()
            try {
                val waitStart = MatrixPerf.now()
                beginTransactionWithRetry()
                MatrixPerf.end(waitStart) { "db.txn.wait${mainThreadFlag()}" }
            } catch (throwable: Throwable) {
                transactionLock.unlock()
                throw throwable
            }
        }
        // Publish the transaction only once BEGIN has actually succeeded, so a failed begin doesn't
        // leave a dangling entry in the ThreadLocal.
        val transaction = Transaction(enclosing).also {
            if (enclosing == null) it.perfHoldStart = MatrixPerf.now()
        }
        transactions.set(transaction)
        return QueryResult.Value(transaction)
    }

    // Backstop for a SQLITE_BUSY that slips past [transactionLock] (e.g. an internal WAL checkpoint on
    // a pool connection). No statements have run yet, so retrying until it clears is safe.
    private fun beginTransactionWithRetry() {
        val deadline = System.currentTimeMillis() + BEGIN_BUSY_TIMEOUT_MS
        var backoff = 2L
        while (true) {
            try {
                database.beginTransaction()
                return
            } catch (locked: SQLiteDatabaseLockedException) {
                if (System.currentTimeMillis() >= deadline) throw locked
                Thread.sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(BEGIN_BUSY_MAX_BACKOFF_MS)
            }
        }
    }

    override fun currentTransaction(): Transacter.Transaction? = transactions.get()

    private inner class Transaction(
            override val enclosingTransaction: Transacter.Transaction?,
    ) : Transacter.Transaction() {

        var perfHoldStart = 0L

        override fun endTransaction(successful: Boolean): QueryResult<Unit> {
            if (enclosingTransaction == null) {
                try {
                    if (successful) {
                        database.setTransactionSuccessful()
                    }
                    database.endTransaction()
                    if (perfHoldStart != 0L) MatrixPerf.end(perfHoldStart) { "db.txn.hold${mainThreadFlag()}" }
                } finally {
                    transactions.set(enclosingTransaction as Transaction?)
                    transactionLock.unlock()
                }
            } else {
                transactions.set(enclosingTransaction as Transaction?)
            }
            return QueryResult.Value(Unit)
        }
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) {
            queryKeys.forEach {
                val set = listeners.getOrPut(it) { linkedSetOf() }
                set.add(listener)
                // A listener leak degrades every commit's notify pass on the write thread — scream early.
                if (set.size % 2048 == 0) {
                    android.util.Log.w("FrameworkSqliteDriver", "Query listener count for '$it' is ${set.size} — probable listener leak")
                }
            }
        }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) {
            queryKeys.forEach { listeners[it]?.remove(listener) }
        }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val toNotify = synchronized(listeners) {
            queryKeys.flatMapTo(linkedSetOf()) { listeners[it].orEmpty() }
        }
        toNotify.forEach { it.queryResultsChanged() }
    }

    override fun close() {
        closed = true
        statementCache.get()!!.values.forEach { runCatching { it.close() } }
        statementCache.remove()
        openHelper?.close()
        if (closeSuppliedDatabase) suppliedDatabase?.close()
    }

    companion object {

        private const val BEGIN_BUSY_TIMEOUT_MS = 5_000L
        private const val BEGIN_BUSY_MAX_BACKOFF_MS = 25L

        /**
         * Open a database at an explicit file path (e.g. inside a per-session directory, so it is
         * removed together with the session on logout). Drops and recreates on a version change,
         * since migrations are out of scope.
         */
        fun create(
                databaseFile: File,
                schema: SqlSchema<QueryResult.Value<Unit>>,
        ): FrameworkSqliteDriver {
            databaseFile.parentFile?.mkdirs()
            val database = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
            val driver = FrameworkSqliteDriver(null, database, closeSuppliedDatabase = true)
            val schemaVersion = schema.version.toInt()
            when (database.version) {
                0 -> {
                    schema.create(driver)
                    database.version = schemaVersion
                }
                schemaVersion -> Unit
                else -> {
                    dropAllContents(database)
                    schema.create(driver)
                    database.version = schemaVersion
                }
            }
            return driver
        }

        /**
         * @param name database file name, or null for an in-memory database (useful for tests).
         */
        fun create(
                context: Context,
                name: String?,
                schema: SqlSchema<QueryResult.Value<Unit>>,
        ): FrameworkSqliteDriver {
            val helper = object : SQLiteOpenHelper(context, name, null, schema.version.toInt()) {
                override fun onCreate(db: SQLiteDatabase) {
                    schema.create(FrameworkSqliteDriver(db))
                }

                // Migrations are out of scope: a version bump drops everything and recreates.
                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    dropAllContents(db)
                    schema.create(FrameworkSqliteDriver(db))
                }

                override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    onUpgrade(db, oldVersion, newVersion)
                }
            }
            return FrameworkSqliteDriver(helper)
        }

        private fun dropAllContents(db: SQLiteDatabase) {
            val drops = mutableListOf<String>()
            db.rawQuery(
                    "SELECT type, name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name != 'android_metadata'",
                    null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    drops.add("DROP ${cursor.getString(0)} IF EXISTS \"${cursor.getString(1)}\"")
                }
            }
            drops.forEach { db.execSQL(it) }
        }
    }
}

/** Marks perf log lines for DB work running on the main thread — always a jank bug. */
private fun mainThreadFlag(): String =
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) ".MAIN-THREAD" else ""

/** First ~70 chars of the SQL, collapsed whitespace, enough to identify the query in the log. */
private fun String.perfSnippet(): String {
    val flat = replace('\n', ' ')
    return if (flat.length <= 70) flat else flat.substring(0, 70)
}

/** Binds typed SQLDelight parameters (0-based) onto a framework [SQLiteProgram] (1-based). */
private class FrameworkProgramBinder(private val program: SQLiteProgram) : SqlPreparedStatement {

    override fun bindBytes(index: Int, bytes: ByteArray?) {
        if (bytes == null) program.bindNull(index + 1) else program.bindBlob(index + 1, bytes)
    }

    override fun bindLong(index: Int, long: Long?) {
        if (long == null) program.bindNull(index + 1) else program.bindLong(index + 1, long)
    }

    override fun bindDouble(index: Int, double: Double?) {
        if (double == null) program.bindNull(index + 1) else program.bindDouble(index + 1, double)
    }

    override fun bindString(index: Int, string: String?) {
        if (string == null) program.bindNull(index + 1) else program.bindString(index + 1, string)
    }

    override fun bindBoolean(index: Int, boolean: Boolean?) {
        if (boolean == null) program.bindNull(index + 1) else program.bindLong(index + 1, if (boolean) 1L else 0L)
    }
}

private class BindingCursorFactory(
        private val binders: SqlPreparedStatement.() -> Unit,
) : SQLiteDatabase.CursorFactory {

    override fun newCursor(
            db: SQLiteDatabase?,
            masterQuery: SQLiteCursorDriver?,
            editTable: String?,
            query: SQLiteQuery,
    ): Cursor {
        FrameworkProgramBinder(query).binders()
        return SQLiteCursor(masterQuery, editTable, query)
    }
}

private class FrameworkCursor(private val cursor: Cursor) : SqlCursor {

    override fun next(): QueryResult<Boolean> = QueryResult.Value(cursor.moveToNext())

    override fun getString(index: Int): String? = if (cursor.isNull(index)) null else cursor.getString(index)

    override fun getLong(index: Int): Long? = if (cursor.isNull(index)) null else cursor.getLong(index)

    override fun getBytes(index: Int): ByteArray? = if (cursor.isNull(index)) null else cursor.getBlob(index)

    override fun getDouble(index: Int): Double? = if (cursor.isNull(index)) null else cursor.getDouble(index)

    override fun getBoolean(index: Int): Boolean? = if (cursor.isNull(index)) null else cursor.getLong(index) == 1L
}

/** Cancellation, not a failure: the session this database belonged to has been released. */
internal class SessionDatabaseClosedException : java.util.concurrent.CancellationException("session database is closed")
