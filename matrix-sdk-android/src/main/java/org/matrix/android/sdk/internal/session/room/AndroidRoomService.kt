/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room

import androidx.lifecycle.LiveData
import androidx.paging.PagedList
import org.matrix.android.sdk.api.session.room.RoomPagingService
import org.matrix.android.sdk.api.session.room.RoomService
import org.matrix.android.sdk.api.session.room.RoomSortOrder
import org.matrix.android.sdk.api.session.room.RoomSummaryQueryParams
import org.matrix.android.sdk.api.session.room.UpdatableLivePageResult
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.room.summary.RoomSummaryDataSource
import org.matrix.android.sdk.internal.session.room.summary.getAllRoomSummaryChildOfLive
import org.matrix.android.sdk.internal.session.room.summary.getFlattenOrphanRoomsLive
import org.matrix.android.sdk.internal.session.room.summary.getSortedPagedRoomSummariesLive
import org.matrix.android.sdk.internal.session.room.summary.getUpdatablePagedRoomSummariesLive
import javax.inject.Inject

/**
 * The RoomService the android app gets: [DefaultRoomService] (plain-JVM) plus the paged and LiveData
 * room-summary views, which need androidx.paging.
 */
@SessionScope
internal class AndroidRoomService @Inject constructor(
        val delegate: DefaultRoomService,
        private val roomSummaryDataSource: RoomSummaryDataSource,
) : RoomService by delegate, RoomPagingService {

    override fun getPagedRoomSummariesLive(
            queryParams: RoomSummaryQueryParams,
            pagedListConfig: PagedList.Config,
            sortOrder: RoomSortOrder
    ): LiveData<PagedList<RoomSummary>> {
        return roomSummaryDataSource.getSortedPagedRoomSummariesLive(queryParams, pagedListConfig, sortOrder)
    }

    override fun getFilteredPagedRoomSummariesLive(
            queryParams: RoomSummaryQueryParams,
            pagedListConfig: PagedList.Config,
            sortOrder: RoomSortOrder,
    ): UpdatableLivePageResult {
        return roomSummaryDataSource.getUpdatablePagedRoomSummariesLive(queryParams, pagedListConfig, sortOrder)
    }

    override fun getFlattenRoomSummaryChildrenOfLive(spaceId: String?, memberships: List<Membership>): LiveData<List<RoomSummary>> {
        if (spaceId == null) {
            return roomSummaryDataSource.getFlattenOrphanRoomsLive()
        }
        return roomSummaryDataSource.getAllRoomSummaryChildOfLive(spaceId, memberships)
    }
}
