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

/**
 * Build a Paging-2 [PagedList] LiveData backed by a SQLDelight [query]: each time the query's results
 * change, [fetch] is re-run to produce a fresh snapshot and the page list is invalidated. This is the
 * replacement for Monarchy's `findAllPagedWithChanges`.
 */
internal fun <T> livePaged(
        query: Query<*>,
        pageSize: Int = 20,
        fetch: () -> List<T>,
): LiveData<PagedList<T>> {
    val factory = object : DataSource.Factory<Int, T>() {
        override fun create(): DataSource<Int, T> = SnapshotPositionalDataSource(query, fetch())
    }
    return LivePagedListBuilder(
            factory,
            PagedList.Config.Builder().setPageSize(pageSize).setEnablePlaceholders(false).setPrefetchDistance(1).build(),
    ).build()
}

/**
 * A Paging-2 [PositionalDataSource] over an in-memory snapshot, invalidated whenever the backing
 * SQLDelight query changes (which recreates it with a fresh snapshot).
 */
private class SnapshotPositionalDataSource<T>(
        private val query: Query<*>,
        private val data: List<T>,
) : PositionalDataSource<T>() {

    private val listener = object : Query.Listener {
        override fun queryResultsChanged() {
            invalidate()
        }
    }

    init {
        query.addListener(listener)
        addInvalidatedCallback { query.removeListener(listener) }
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
