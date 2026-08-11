/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.paging.PagedList
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.util.Optional

/**
 * Android-only LiveData view over [RoomService.getRoomSummariesFlow], for consumers that observe via a
 * lifecycle owner.
 */
fun RoomService.getRoomSummariesLive(
        queryParams: RoomSummaryQueryParams,
        sortOrder: RoomSortOrder = RoomSortOrder.ACTIVITY,
): LiveData<List<RoomSummary>> = getRoomSummariesFlow(queryParams, sortOrder).asLiveData()

/**
 * Android-only LiveData views over the room-summary Flows. The services expose platform-neutral Flows
 * (so they can live in the shared core); consumers that still want LiveData use these. Not part of the
 * core module.
 */
fun Room.getRoomSummaryLive(): LiveData<Optional<RoomSummary>> = getRoomSummaryFlow().asLiveData()

fun RoomService.getRoomSummaryLive(roomId: String): LiveData<Optional<RoomSummary>> = getRoomSummaryFlow(roomId).asLiveData()

/**
 * Android-only paged / LiveData room-summary views, kept off [RoomService] (which stays plain-JVM, no
 * androidx.paging/lifecycle). The android RoomService impl also implements this; cast roomService() to
 * reach it.
 */
interface RoomPagingService {

    fun getPagedRoomSummariesLive(
            queryParams: RoomSummaryQueryParams,
            pagedListConfig: PagedList.Config = defaultPagedListConfig,
            sortOrder: RoomSortOrder = RoomSortOrder.ACTIVITY
    ): LiveData<PagedList<RoomSummary>>

    fun getFilteredPagedRoomSummariesLive(
            queryParams: RoomSummaryQueryParams,
            pagedListConfig: PagedList.Config = defaultPagedListConfig,
            sortOrder: RoomSortOrder = RoomSortOrder.ACTIVITY,
    ): UpdatableLivePageResult

    fun getFlattenRoomSummaryChildrenOfLive(
            spaceId: String?,
            memberships: List<Membership> = Membership.activeMemberships()
    ): LiveData<List<RoomSummary>>

    private val defaultPagedListConfig
        get() = PagedList.Config.Builder()
                .setPageSize(10)
                .setInitialLoadSizeHint(20)
                .setEnablePlaceholders(false)
                .setPrefetchDistance(10)
                .build()
}
