/*
 * Copyright 2022 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.room.threads

import androidx.lifecycle.MutableLiveData
import androidx.paging.PagedList
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.matrix.android.sdk.api.session.room.ResultBoundaries
import org.matrix.android.sdk.api.session.room.threads.FetchThreadsResult
import org.matrix.android.sdk.api.session.room.threads.ThreadFilter
import org.matrix.android.sdk.api.session.room.threads.ThreadLivePageResult
import org.matrix.android.sdk.api.session.room.threads.ThreadsService
import org.matrix.android.sdk.api.session.room.threads.model.ThreadSummary
import org.matrix.android.sdk.internal.database.mapper.ThreadSummaryMapper
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.livePaged
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.room.relation.threads.FetchThreadSummariesTask
import org.matrix.android.sdk.internal.session.room.relation.threads.FetchThreadTimelineTask

internal class DefaultThreadsService @AssistedInject constructor(
        @Assisted private val roomId: String,
        private val fetchThreadTimelineTask: FetchThreadTimelineTask,
        @SessionDatabase private val database: SessionSqlDatabase,
        private val stores: SessionStores,
        private val threadSummaryMapper: ThreadSummaryMapper,
        private val fetchThreadSummariesTask: FetchThreadSummariesTask,
) : ThreadsService {

    @AssistedFactory
    interface Factory {
        fun create(roomId: String): DefaultThreadsService
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

    override suspend fun fetchThreadList(nextBatchId: String?, limit: Int, filter: ThreadFilter): FetchThreadsResult {
        return fetchThreadSummariesTask.execute(
                FetchThreadSummariesTask.Params(
                        roomId = roomId,
                        from = nextBatchId,
                        limit = limit,
                        filter = filter
                )
        )
    }

    override suspend fun getAllThreadSummaries(): List<ThreadSummary> {
        return enhanceThreadWithEditions(stores.threadSummary.getByRoomSortedByLatest(roomId).map { threadSummaryMapper.map(it) })
    }

    override fun enhanceThreadWithEditions(threads: List<ThreadSummary>): List<ThreadSummary> {
        return threads.map {
            addEditionIfNeeded(it, enhanceRoot = true)
            addEditionIfNeeded(it, enhanceRoot = false)
            it
        }
    }

    private fun addEditionIfNeeded(summary: ThreadSummary, enhanceRoot: Boolean) {
        val eventId = if (enhanceRoot) summary.rootEventId else summary.latestEvent?.eventId ?: return
        val editedEventId = stores.annotations.get(eventId)?.editSummary?.editions?.lastOrNull()?.eventId ?: return
        val editedEvent = stores.event.getByEventIdInRoom(roomId, editedEventId) ?: return
        val editedText = editedEvent.asDomain().getDecryptedTextSummary() ?: "(edited)"
        if (enhanceRoot) {
            summary.threadEditions.rootThreadEdition = editedText
        } else {
            summary.threadEditions.latestThreadEdition = editedText
        }
    }

    override suspend fun fetchThreadTimeline(rootThreadEventId: String, from: String, limit: Int) {
        fetchThreadTimelineTask.execute(
                FetchThreadTimelineTask.Params(
                        roomId = roomId,
                        rootThreadEventId = rootThreadEventId,
                        from = from,
                        limit = limit
                )
        )
    }
}
