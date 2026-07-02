/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sqldelight

import androidx.lifecycle.LiveData
import androidx.paging.DataSource
import androidx.paging.LivePagedListBuilder
import androidx.paging.PagedList
import androidx.paging.PositionalDataSource
import app.cash.sqldelight.Query
import org.matrix.android.sdk.api.util.MatrixPerf

/** Identify the backing query in perf logs (the generated Query class name is table+query). */
private fun describeQuery(query: Query<*>): String = query.javaClass.simpleName.ifEmpty { query.javaClass.name.substringAfterLast('.') }

/**
 * Build a Paging-2 [PagedList] LiveData backed by a SQLDelight [query]: each time the query's results
 * change, [fetch] is re-run to produce a fresh snapshot and the page list is invalidated. This is the
 * replacement for Monarchy's `findAllPagedWithChanges`.
 *
 * The caller's [config] is used verbatim — in particular its placeholder setting. Placeholders keep the
 * list size stable across invalidations so scroll position survives a sync (and the placeholder rows are
 * the room list's "loading" indicator); disabling them makes the list re-page from the top on every sync.
 */
internal fun <T> livePaged(
        query: Query<*>,
        config: PagedList.Config,
        onDataSourceCreated: ((DataSource<Int, T>) -> Unit)? = null,
        // A shared single-thread executor makes several lists load FIFO (in the order they're observed)
        // instead of racing on the default IO pool — used so the room-list sections populate in order.
        fetchExecutor: java.util.concurrent.Executor? = null,
        fetch: () -> List<T>,
): LiveData<PagedList<T>> {
    val factory = object : DataSource.Factory<Int, T>() {
        override fun create(): DataSource<Int, T> {
            val perfStart = MatrixPerf.now()
            val data = fetch()
            MatrixPerf.end(perfStart) { "paging.fetch items=${data.size} [${describeQuery(query)}]" }
            return SnapshotPositionalDataSource(query, data).also { onDataSourceCreated?.invoke(it) }
        }
    }
    return LivePagedListBuilder(factory, config)
            .apply { fetchExecutor?.let { setFetchExecutor(it) } }
            .build()
}

/** Convenience overload for callers that only care about page size (no placeholders). */
internal fun <T> livePaged(
        query: Query<*>,
        pageSize: Int = 20,
        onDataSourceCreated: ((DataSource<Int, T>) -> Unit)? = null,
        fetch: () -> List<T>,
): LiveData<PagedList<T>> = livePaged(
        query = query,
        config = PagedList.Config.Builder().setPageSize(pageSize).setEnablePlaceholders(false).setPrefetchDistance(1).build(),
        onDataSourceCreated = onDataSourceCreated,
        fetch = fetch,
)

/**
 * A Paging-2 [PositionalDataSource] over an in-memory snapshot, invalidated whenever the backing
 * SQLDelight query changes (which recreates it with a fresh snapshot).
 */
private class SnapshotPositionalDataSource<T>(
        private val query: Query<*>,
        private val data: List<T>,
) : PositionalDataSource<T>() {

    private val invalidatePending = java.util.concurrent.atomic.AtomicBoolean(false)

    private val listener = object : Query.Listener {
        override fun queryResultsChanged() {
            // Coalesce write bursts: a sync (or a batch of preview decryptions) hits the table many
            // times in quick succession, and every invalidation resets the PagedList to placeholders and
            // re-fetches all sections on the shared executor — starving the page loads a live scroll needs.
            if (invalidatePending.compareAndSet(false, true)) {
                MatrixPerf.note { "paging.invalidate [${describeQuery(query)}]" }
                coalesceExecutor.schedule({ invalidate() }, INVALIDATE_COALESCE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
    }

    init {
        query.addListener(listener)
        addInvalidatedCallback { query.removeListener(listener) }
    }

    private companion object {
        private const val INVALIDATE_COALESCE_MS = 250L
        private val coalesceExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "db-paging-invalidate").apply { isDaemon = true }
        }
    }

    override fun loadInitial(params: LoadInitialParams, callback: LoadInitialCallback<T>) {
        val total = data.size
        val position = computeInitialLoadPosition(params, total)
        val size = computeInitialLoadSize(params, position, total)
        callback.onResult(data.subList(position, position + size), position, total)
    }

    override fun loadRange(params: LoadRangeParams, callback: LoadRangeCallback<T>) {
        if (params.startPosition >= data.size) {
            callback.onResult(emptyList())
        } else {
            callback.onResult(data.subList(params.startPosition, minOf(params.startPosition + params.loadSize, data.size)))
        }
    }
}
