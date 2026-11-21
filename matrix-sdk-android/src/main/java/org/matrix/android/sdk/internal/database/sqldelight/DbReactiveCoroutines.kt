/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sqldelight

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.TransactionWithReturn
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.debug.DiagnosticContext
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * Per-database single-thread dispatcher. SQLDelight tracks the active transaction in a
 * [ThreadLocal], so all access to a given database MUST stay on one thread — a serialising-but-
 * thread-hopping dispatcher (e.g. limitedParallelism(1)) would break transaction nesting.
 */
internal fun newDatabaseDispatcher(name: String): CoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, name) }.asCoroutineDispatcher()

/**
 * Replacement for `awaitTransaction(realmConfiguration) { ... }`. Runs the body inside a SQLDelight
 * transaction on the database's dedicated thread and returns its result.
 */
internal suspend fun <T> Transacter.awaitDbTransaction(
        dispatcher: CoroutineDispatcher,
        body: TransactionWithReturn<T>.() -> T,
): T {
    val enqueuedAt = System.nanoTime()
    return withContext(dispatcher) {
        val startedAt = System.nanoTime()
        var enteredAt: Long? = null
        var stage = "begin"
        var failure: Throwable? = null
        try {
            transactionWithResult {
                enteredAt = System.nanoTime()
                stage = "body"
                body().also { stage = "commit" }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val finishedAt = System.nanoTime()
            val waited = (startedAt - enqueuedAt) / 1_000_000
            val ran = (finishedAt - startedAt) / 1_000_000
            if (waited > 500 || ran > 500) {
                Timber.w("## DB: slow transaction ${body.javaClass.name}: waited ${waited}ms, ran ${ran}ms")
            }
            if (failure != null || waited > 100 || ran > 100) DebugLog.i {
                "DBDBG transaction site=${body.javaClass.name} dispatchWaitMs=$waited " +
                        "beginWaitMs=${((enteredAt ?: finishedAt) - startedAt) / 1_000_000} " +
                        "bodyAndCommitMs=${enteredAt?.let { (finishedAt - it) / 1_000_000 }} stage=$stage " +
                        "outcome=${if (failure == null) "returned" else "failed"} error=${failure?.javaClass?.simpleName} ${DiagnosticContext.describe()}"
            }
        }
    }
}

/**
 * Replacement for `awaitNotEmptyResult(realmConfiguration, ...)`: suspend until [query] first yields a
 * non-empty result, or throw [kotlinx.coroutines.TimeoutCancellationException] after [timeoutMillis].
 */
internal suspend fun <T : Any> awaitNotEmptyResult(
        query: Query<T>,
        timeoutMillis: Long,
        dispatcher: CoroutineDispatcher,
) {
    withTimeout(timeoutMillis) {
        query.asFlow().mapToList(dispatcher).first { it.isNotEmpty() }
    }
}
