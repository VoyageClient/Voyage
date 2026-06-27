/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sqldelight

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.TransactionWithReturn
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors

/**
 * Per-database single-thread dispatcher. SQLDelight tracks the active transaction in a
 * [ThreadLocal], so all access to a given database MUST stay on one thread — a serialising-but-
 * thread-hopping dispatcher (e.g. limitedParallelism(1)) would break transaction nesting.
 */
internal fun newDatabaseDispatcher(name: String): CoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, name) }.asCoroutineDispatcher()

/** Replacement for `monarchy.findAllMappedWithChanges` — an auto-updating list as LiveData. */
internal fun <T : Any> Query<T>.asLiveList(dispatcher: CoroutineDispatcher): LiveData<List<T>> =
        asFlow().mapToList(dispatcher).asLiveData()

/** Replacement for a single-result managed-with-changes query. */
internal fun <T : Any> Query<T>.asLiveOneOrNull(dispatcher: CoroutineDispatcher): LiveData<T?> =
        asFlow().mapToOneOrNull(dispatcher).asLiveData()

/**
 * Replacement for `awaitTransaction(realmConfiguration) { ... }`. Runs the body inside a SQLDelight
 * transaction on the database's dedicated thread and returns its result.
 */
internal suspend fun <T> Transacter.awaitDbTransaction(
        dispatcher: CoroutineDispatcher,
        body: TransactionWithReturn<T>.() -> T,
): T = withContext(dispatcher) {
    transactionWithResult(bodyWithReturn = body)
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
