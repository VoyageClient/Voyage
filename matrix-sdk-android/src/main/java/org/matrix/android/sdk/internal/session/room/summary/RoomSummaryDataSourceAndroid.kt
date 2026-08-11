/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.summary

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import androidx.lifecycle.switchMap
import androidx.paging.DataSource
import androidx.paging.PagedList
import org.matrix.android.sdk.api.query.RoomCategoryFilter
import org.matrix.android.sdk.api.session.room.ResultBoundaries
import org.matrix.android.sdk.api.session.room.RoomSortOrder
import org.matrix.android.sdk.api.session.room.RoomSummaryQueryParams
import org.matrix.android.sdk.api.session.room.UpdatableLivePageResult
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.RoomType
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.database.sqldelight.asLiveList
import org.matrix.android.sdk.internal.database.sqldelight.livePaged
import java.util.concurrent.atomic.AtomicReference

// LiveData/PagedList views over RoomSummaryDataSource for the android internal consumers
// (DefaultRoomService, RoomServiceAndroid, HierarchyLiveDataHelper). The Flow-returning methods on
// the data source are the primary surface; these just adapt them (or the paging queries) to LiveData.

internal fun RoomSummaryDataSource.getRoomSummariesLive(
        queryParams: RoomSummaryQueryParams,
        sortOrder: RoomSortOrder = RoomSortOrder.NONE
): LiveData<List<RoomSummary>> =
        flowOnRoomSummaryChange { filteredSortedSummaries(queryParams, sortOrder) }.asLiveData()

internal fun RoomSummaryDataSource.getSpaceSummaryLive(roomId: String): LiveData<Optional<RoomSummary>> =
        queries.selectByRoomId(roomId).asLiveList(dispatcher).map { rows ->
            rows.firstOrNull { !it.display_name.isNullOrEmpty() && it.room_type == RoomType.SPACE }?.let { rowToDomain(it) }.toOptional()
        }

internal fun RoomSummaryDataSource.getAllRoomSummaryChildOfLive(
        spaceId: String,
        memberShips: List<Membership>
): LiveData<List<RoomSummary>> {
    val mediatorLiveData = HierarchyLiveDataHelper(spaceId, memberShips, this).liveData()
    return mediatorLiveData.switchMap { allIds ->
        queries.selectAll().asLiveList(dispatcher).map { rows ->
            rows.filter {
                it.room_id in allIds && it.membership_str in memberShips.map { m -> m.name } && it.is_direct == 0L
            }.mapNotNull { rowToDomain(it) }
        }
    }
}

internal fun RoomSummaryDataSource.getFlattenOrphanRoomsLive(): LiveData<List<RoomSummary>> =
        getRoomSummariesLive(roomSummaryQueryParams {
            memberships = Membership.activeMemberships()
            excludeType = listOf(RoomType.SPACE)
            roomCategoryFilter = RoomCategoryFilter.ONLY_ROOMS
        }).map { it.filter { summary -> isOrphan(summary) } }

internal fun RoomSummaryDataSource.getSortedPagedRoomSummariesLive(
        queryParams: RoomSummaryQueryParams,
        pagedListConfig: PagedList.Config,
        sortOrder: RoomSortOrder,
): LiveData<PagedList<RoomSummary>> =
        livePaged(queries.selectAll(), pagedListConfig) {
            filteredSortedSummaries(queryParams, sortOrder)
        }

internal fun RoomSummaryDataSource.getUpdatablePagedRoomSummariesLive(
        queryParams: RoomSummaryQueryParams,
        pagedListConfig: PagedList.Config,
        sortOrder: RoomSortOrder,
): UpdatableLivePageResult {
    val boundaries = MutableLiveData(ResultBoundaries())
    // selectAll() only re-emits on DB writes, so reassigning queryParams/sortOrder must invalidate the
    // DataSource to re-run the filter — else the list wouldn't refresh until the next sync.
    val dataSourceRef = AtomicReference<DataSource<Int, RoomSummary>?>(null)
    return object : UpdatableLivePageResult {
        override var queryParams: RoomSummaryQueryParams = queryParams
            set(value) {
                field = value
                dataSourceRef.get()?.invalidate()
            }
        override var sortOrder: RoomSortOrder = sortOrder
            set(value) {
                field = value
                dataSourceRef.get()?.invalidate()
            }
        override val liveBoundaries: LiveData<ResultBoundaries> get() = boundaries
        override val livePagedList: LiveData<PagedList<RoomSummary>> =
                livePaged(queries.selectAll(), pagedListConfig, onDataSourceCreated = { dataSourceRef.set(it) }, fetchExecutor = sectionFetchExecutor) {
                    filteredSortedSummaries(this.queryParams, this.sortOrder)
                            .also { boundaries.postValue(ResultBoundaries(zeroItemLoaded = it.isEmpty())) }
                }
    }
}
