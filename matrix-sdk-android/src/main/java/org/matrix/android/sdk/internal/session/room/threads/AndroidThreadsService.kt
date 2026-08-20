/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.threads

import androidx.lifecycle.MutableLiveData
import androidx.paging.PagedList
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.matrix.android.sdk.api.session.room.ResultBoundaries
import org.matrix.android.sdk.api.session.room.threads.ThreadLivePageResult
import org.matrix.android.sdk.api.session.room.threads.ThreadsPagingService
import org.matrix.android.sdk.api.session.room.threads.ThreadsService
import org.matrix.android.sdk.internal.database.mapper.ThreadSummaryMapper
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.livePaged
import org.matrix.android.sdk.internal.di.SessionDatabase

/**
 * The ThreadsService the android app gets: [DefaultThreadsService] (plain-JVM) plus the paged thread
 * list, which needs androidx.paging.
 */
internal class AndroidThreadsService @AssistedInject constructor(
        @Assisted private val roomId: String,
        threadsServiceFactory: DefaultThreadsService.Factory,
        @SessionDatabase private val database: SessionSqlDatabase,
        private val stores: SessionStores,
        private val threadSummaryMapper: ThreadSummaryMapper,
) : ThreadsService by threadsServiceFactory.create(roomId), ThreadsPagingService {

    @AssistedFactory
    interface Factory : ThreadsServiceFactory {
        override fun create(roomId: String): AndroidThreadsService
    }

    override suspend fun getPagedThreadsList(userParticipating: Boolean, pagedListConfig: PagedList.Config): ThreadLivePageResult {
        val livePagedList = livePaged(
                query = database.threadSummaryQueries.selectByRoomSortedByLatest(roomId),
                config = pagedListConfig,
        ) {
            enhanceThreadWithEditions(stores.threadSummary.getByRoomSortedByLatest(roomId).map { threadSummaryMapper.map(it) })
        }
        // Boundary callbacks (front/end/zero loaded) are not reproduced over the snapshot paging source;
        // the UI falls back to fetchThreadList for "load more".
        val boundaries = MutableLiveData(ResultBoundaries())
        return ThreadLivePageResult(livePagedList, boundaries)
    }
}
