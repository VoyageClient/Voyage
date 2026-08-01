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
    val enqueuedAt = System.currentTimeMillis()
    return withContext(dispatcher) {
        val startedAt = System.currentTimeMillis()
        val result = transactionWithResult(bodyWithReturn = body)
        val finishedAt = System.currentTimeMillis()
        val waited = startedAt - enqueuedAt
        val ran = finishedAt - startedAt
        // The lambda class name identifies the call site hogging (ran) or stuck behind (waited) the dispatcher.
        if (waited > 500 || ran > 500) {
            Timber.w("## DB: slow transaction ${body.javaClass.name}: waited ${waited}ms, ran ${ran}ms")
        }
        result
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
