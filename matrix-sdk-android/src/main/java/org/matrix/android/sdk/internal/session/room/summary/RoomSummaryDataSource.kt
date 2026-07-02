/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 * Copyright 2021 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.room.summary

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import androidx.lifecycle.switchMap
import androidx.paging.DataSource
import androidx.paging.PagedList
import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.query.RoomCategoryFilter
import org.matrix.android.sdk.api.query.SpaceFilter
import org.matrix.android.sdk.api.query.isNormalized
import org.matrix.android.sdk.api.session.room.ResultBoundaries
import org.matrix.android.sdk.api.session.room.RoomSortOrder
import org.matrix.android.sdk.api.session.room.RoomSummaryQueryParams
import org.matrix.android.sdk.api.session.room.UpdatableLivePageResult
import org.matrix.android.sdk.api.session.room.model.LocalRoomSummary
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.RoomType
import java.util.concurrent.atomic.AtomicReference
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.api.session.room.spaceSummaryQueryParams
import org.matrix.android.sdk.api.session.room.summary.RoomAggregateNotificationCount
import org.matrix.android.sdk.api.session.space.SpaceSummaryQueryParams
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.database.mapper.LocalRoomSummaryMapper
import org.matrix.android.sdk.internal.database.mapper.RoomSummaryMapper
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.asLiveList
import org.matrix.android.sdk.internal.database.sqldelight.livePaged
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.query.matches
import javax.inject.Inject
import org.matrix.android.sdk.internal.database.sql.Room_summary as RoomSummaryRow

internal class RoomSummaryDataSource @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val roomSummaryMapper: RoomSummaryMapper,
        private val localRoomSummaryMapper: LocalRoomSummaryMapper,
        private val stores: SessionStores,
) {
    private val queries get() = database.roomSummaryQueries

    // The room list's sections each get their own paged list; on the default IO pool they load
    // concurrently and the smallest (usually Low priority) wins the race and briefly shows first. Loading
    // them on one thread makes them populate in the order they're observed (Favourites, Rooms/DMs, Low
    // priority) instead.
    private val sectionFetchExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "room-list-section-paging")
    }

    // Mapping a row is expensive (deep latest-event/space/tag resolution). Memoize by the row itself
    // (a generated data class) so each sync only re-maps rooms whose summary actually changed; a new
    // message mutates the row (latest event id, unread counts, activity) → cache miss → fresh map.
    private val summaryCache = java.util.concurrent.ConcurrentHashMap<RoomSummaryRow, RoomSummary>()

    private fun RoomSummaryRow.toDomain(): RoomSummary? {
        summaryCache[this]?.let { return it }
        val mapped = stores.roomSummary.get(room_id)?.let { roomSummaryMapper.map(it) } ?: return null
        // Changed rooms leave their old row as a dead key; bound growth.
        if (summaryCache.size > 512) summaryCache.clear()
        summaryCache[this] = mapped
        return mapped
    }

    fun getRoomSummary(roomIdOrAlias: String): RoomSummary? {
        val row = if (roomIdOrAlias.startsWith("!")) {
            queries.selectByRoomId(roomIdOrAlias).executeAsOneOrNull()
        } else {
            queries.selectByAlias(roomIdOrAlias, roomIdOrAlias).executeAsOneOrNull()
        }
        return row?.toDomain()
    }

    fun getRoomSummaryLive(roomId: String): LiveData<Optional<RoomSummary>> {
        return queries.selectByRoomId(roomId).asLiveList(dispatcher)
                .map { rows -> rows.firstOrNull { !it.display_name.isNullOrEmpty() }?.toDomain().toOptional() }
    }

    fun getRoomSummaries(queryParams: RoomSummaryQueryParams, sortOrder: RoomSortOrder = RoomSortOrder.NONE): List<RoomSummary> {
        return filteredSortedRows(queryParams, sortOrder).mapNotNull { it.toDomain() }
    }

    fun getLocalRoomSummary(roomId: String): LocalRoomSummary? =
            stores.localRoomSummary.get(roomId)?.let { localRoomSummaryMapper.map(it) }

    fun getLocalRoomSummaryLive(roomId: String): LiveData<Optional<LocalRoomSummary>> {
        return database.localRoomSummaryQueries.selectByRoomId(roomId).asLiveList(dispatcher)
                .map { rows -> rows.firstOrNull()?.let { stores.localRoomSummary.get(it.room_id) }?.let { localRoomSummaryMapper.map(it) }.toOptional() }
    }

    fun getRoomSummariesLive(queryParams: RoomSummaryQueryParams, sortOrder: RoomSortOrder = RoomSortOrder.NONE): LiveData<List<RoomSummary>> {
        return queries.selectAll().asLiveList(dispatcher)
                .map { applyFilterAndSort(it, queryParams, sortOrder).mapNotNull { row -> row.toDomain() } }
    }

    fun getRoomSummariesChangesLive(queryParams: RoomSummaryQueryParams, sortOrder: RoomSortOrder = RoomSortOrder.NONE): LiveData<List<Unit>> {
        return queries.selectAll().asLiveList(dispatcher).map { rows -> applyFilterAndSort(rows, queryParams, sortOrder).map { } }
    }

    fun getSpaceSummariesLive(queryParams: SpaceSummaryQueryParams, sortOrder: RoomSortOrder = RoomSortOrder.NONE): LiveData<List<RoomSummary>> =
            getRoomSummariesLive(queryParams, sortOrder)

    fun getSpaceSummary(roomIdOrAlias: String): RoomSummary? = getRoomSummary(roomIdOrAlias)?.takeIf { it.roomType == RoomType.SPACE }

    fun getSpaceSummaryLive(roomId: String): LiveData<Optional<RoomSummary>> {
        return queries.selectByRoomId(roomId).asLiveList(dispatcher).map { rows ->
            rows.firstOrNull { !it.display_name.isNullOrEmpty() && it.room_type == RoomType.SPACE }?.toDomain().toOptional()
        }
    }

    fun getSpaceSummaries(spaceSummaryQueryParams: SpaceSummaryQueryParams, sortOrder: RoomSortOrder = RoomSortOrder.NONE): List<RoomSummary> =
            getRoomSummaries(spaceSummaryQueryParams, sortOrder)

    fun getRootSpaceSummaries(): List<RoomSummary> {
        return getRoomSummaries(spaceSummaryQueryParams { memberships = listOf(Membership.JOIN) })
                .let { allJoinedSpace ->
                    val allFlattenChildren = arrayListOf<RoomSummary>()
                    allJoinedSpace.forEach { flattenSubSpace(it, emptyList(), allFlattenChildren, listOf(Membership.JOIN), false) }
                    val knownNonOrphan = allFlattenChildren.map { it.roomId }.distinct()
                    allJoinedSpace.filter { candidate -> !knownNonOrphan.contains(candidate.roomId) }
                }
    }

    fun getBreadcrumbs(queryParams: RoomSummaryQueryParams): List<RoomSummary> {
        return filteredSortedRows(queryParams, RoomSortOrder.NONE)
                .filter { it.breadcrumbs_index > RoomSummary.NOT_IN_BREADCRUMBS }
                .sortedBy { it.breadcrumbs_index }
                .mapNotNull { it.toDomain() }
    }

    fun getBreadcrumbsLive(queryParams: RoomSummaryQueryParams): LiveData<List<RoomSummary>> {
        return queries.selectAll().asLiveList(dispatcher).map { rows ->
            applyFilterAndSort(rows, queryParams, RoomSortOrder.NONE)
                    .filter { it.breadcrumbs_index > RoomSummary.NOT_IN_BREADCRUMBS }
                    .sortedBy { it.breadcrumbs_index }
                    .mapNotNull { it.toDomain() }
        }
    }

    fun getSortedPagedRoomSummariesLive(
            queryParams: RoomSummaryQueryParams,
            pagedListConfig: PagedList.Config,
            sortOrder: RoomSortOrder,
    ): LiveData<PagedList<RoomSummary>> {
        return livePaged(queries.selectAll(), pagedListConfig) {
            filteredSortedRows(queryParams, sortOrder).mapNotNull { it.toDomain() }
        }
    }

    fun getUpdatablePagedRoomSummariesLive(
            queryParams: RoomSummaryQueryParams,
            pagedListConfig: PagedList.Config,
            sortOrder: RoomSortOrder,
    ): UpdatableLivePageResult {
        val boundaries = MutableLiveData(ResultBoundaries())
        // selectAll() only re-emits on DB writes, so reassigning queryParams/sortOrder must invalidate the
        // DataSource to re-run the filter — else the list wouldn't refresh until the next sync.
        val dataSourceRef = AtomicReference<DataSource<Int, RoomSummary>?>(null)
        val result = object : UpdatableLivePageResult {
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
                        filteredSortedRows(this.queryParams, this.sortOrder).mapNotNull { it.toDomain() }
                                .also { boundaries.postValue(ResultBoundaries(zeroItemLoaded = it.isEmpty())) }
                    }
        }
        return result
    }

    fun getCountLive(queryParams: RoomSummaryQueryParams): LiveData<Int> {
        return queries.selectAll().asLiveList(dispatcher).map { applyFilterAndSort(it, queryParams, RoomSortOrder.NONE).size }
    }

    fun getNotificationCountForRooms(queryParams: RoomSummaryQueryParams): RoomAggregateNotificationCount {
        val rows = filteredSortedRows(queryParams, RoomSortOrder.NONE)
        return RoomAggregateNotificationCount(
                rows.sumOf { it.notification_count.toInt() },
                rows.sumOf { it.highlight_count.toInt() },
        )
    }

    fun getAllRoomSummaryChildOf(spaceAliasOrId: String, memberShips: List<Membership>): List<RoomSummary> {
        val space = getSpaceSummary(spaceAliasOrId) ?: return emptyList()
        val result = ArrayList<RoomSummary>()
        flattenChild(space, emptyList(), result, memberShips)
        return result
    }

    fun getAllRoomSummaryChildOfLive(spaceId: String, memberShips: List<Membership>): LiveData<List<RoomSummary>> {
        val mediatorLiveData = HierarchyLiveDataHelper(spaceId, memberShips, this).liveData()
        return mediatorLiveData.switchMap { allIds ->
            queries.selectAll().asLiveList(dispatcher).map { rows ->
                rows.filter {
                    it.room_id in allIds && it.membership_str in memberShips.map { m -> m.name } && it.is_direct == 0L
                }.mapNotNull { it.toDomain() }
            }
        }
    }

    fun getFlattenOrphanRooms(): List<RoomSummary> {
        return getRoomSummaries(roomSummaryQueryParams {
            memberships = Membership.activeMemberships()
            excludeType = listOf(RoomType.SPACE)
            roomCategoryFilter = RoomCategoryFilter.ONLY_ROOMS
        }).filter { isOrphan(it) }
    }

    fun getFlattenOrphanRoomsLive(): LiveData<List<RoomSummary>> {
        return getRoomSummariesLive(roomSummaryQueryParams {
            memberships = Membership.activeMemberships()
            excludeType = listOf(RoomType.SPACE)
            roomCategoryFilter = RoomCategoryFilter.ONLY_ROOMS
        }).map { it.filter { summary -> isOrphan(summary) } }
    }

    private fun isOrphan(roomSummary: RoomSummary): Boolean {
        if (roomSummary.roomType == RoomType.SPACE && roomSummary.membership.isActive()) {
            return false
        }
        roomSummary.spaceParents?.forEach { info ->
            if (info.roomSummary != null && !info.roomSummary.membership.isLeft()) {
                if (!isOrphan(info.roomSummary)) return false
            }
        }
        for (spaceSummary in getSpaceSummaries(spaceSummaryQueryParams { memberships = Membership.activeMemberships() })) {
            if (spaceSummary.spaceChildren?.any { it.childRoomId == roomSummary.roomId } == true) return false
        }
        return true
    }

    fun flattenChild(current: RoomSummary, parenting: List<String>, output: MutableList<RoomSummary>, memberShips: List<Membership>) {
        current.spaceChildren?.sortedBy { it.order ?: it.name }?.forEach { childInfo ->
            if (childInfo.roomType == RoomType.SPACE) {
                if (!parenting.contains(childInfo.childRoomId)) {
                    getSpaceSummary(childInfo.childRoomId)?.let { subSpace ->
                        if (memberShips.isEmpty() || memberShips.contains(subSpace.membership)) {
                            flattenChild(subSpace, parenting + listOf(current.roomId), output, memberShips)
                        }
                    }
                }
            } else if (childInfo.isKnown) {
                getRoomSummary(childInfo.childRoomId)?.let {
                    if (memberShips.isEmpty() || memberShips.contains(it.membership)) {
                        if (!it.isDirect) output.add(it)
                    }
                }
            }
        }
    }

    fun flattenSubSpace(
            current: RoomSummary,
            parenting: List<String>,
            output: MutableList<RoomSummary>,
            memberShips: List<Membership>,
            includeCurrent: Boolean = true,
    ) {
        if (includeCurrent) output.add(current)
        current.spaceChildren?.sortedBy { it.order ?: it.name }?.forEach {
            if (it.roomType == RoomType.SPACE) {
                if (!parenting.contains(it.childRoomId)) {
                    getSpaceSummary(it.childRoomId)?.let { subSpace ->
                        if (memberShips.isEmpty() || memberShips.contains(subSpace.membership)) {
                            output.add(subSpace)
                            flattenSubSpace(subSpace, parenting + listOf(current.roomId), output, memberShips)
                        }
                    }
                }
            }
        }
    }

    private fun filteredSortedRows(queryParams: RoomSummaryQueryParams, sortOrder: RoomSortOrder): List<RoomSummaryRow> =
            applyFilterAndSort(queries.selectAll().executeAsList(), queryParams, sortOrder)

    private fun applyFilterAndSort(rows: List<RoomSummaryRow>, queryParams: RoomSummaryQueryParams, sortOrder: RoomSortOrder): List<RoomSummaryRow> =
            sort(rows.filter { it.matches(queryParams) }, sortOrder)

    private fun RoomSummaryRow.matches(p: RoomSummaryQueryParams): Boolean {
        if (room_id.isEmpty()) return false
        if (!p.roomId.matches(room_id)) return false
        val displayNameField = if (p.displayName.isNormalized()) normalized_display_name else display_name
        if (!p.displayName.matches(displayNameField)) return false
        if (!p.canonicalAlias.matches(canonical_alias)) return false
        if (p.memberships.isNotEmpty() && membership_str !in p.memberships.map { it.name }) return false
        if (is_hidden_from_user != 0L) return false

        p.roomTagQueryFilter?.let { f ->
            f.isFavorite?.let { if ((is_favourite != 0L) != it) return false }
            f.isLowPriority?.let { if ((is_low_priority != 0L) != it) return false }
            f.isServerNotice?.let { if ((is_server_notice != 0L) != it) return false }
        }
        p.excludeType?.let { if (room_type in it) return false }
        p.includeType?.let { if (room_type !in it) return false }
        when (p.roomCategoryFilter) {
            RoomCategoryFilter.ONLY_DM -> if (is_direct == 0L) return false
            RoomCategoryFilter.ONLY_ROOMS -> if (is_direct != 0L) return false
            RoomCategoryFilter.ONLY_WITH_NOTIFICATIONS -> if (notification_count <= 0L) return false
            null -> Unit
        }
        when (val sf = p.spaceFilter) {
            SpaceFilter.OrphanRooms -> if (flatten_parent_ids != null) return false
            is SpaceFilter.ActiveSpace -> if (flatten_parent_ids?.contains(sf.spaceId) != true) return false
            is SpaceFilter.ExcludeSpace -> if (flatten_parent_ids?.contains(sf.spaceId) == true) return false
            SpaceFilter.NoFilter -> Unit
        }
        p.activeTagFilter?.let { tag ->
            if (database.roomTagQueries.selectByRoom(room_id).executeAsList().none { it.tag_name == tag }) return false
        }
        return true
    }

    private fun sort(rows: List<RoomSummaryRow>, sortOrder: RoomSortOrder): List<RoomSummaryRow> = when (sortOrder) {
        RoomSortOrder.NAME -> rows.sortedBy { (it.normalized_display_name ?: it.display_name ?: "").lowercase() }
        RoomSortOrder.ACTIVITY -> rows.sortedByDescending { it.last_activity_time ?: 0L }
        RoomSortOrder.PRIORITY_AND_ACTIVITY -> rows.sortedWith(
                compareByDescending<RoomSummaryRow> { it.is_favourite }
                        .thenBy { it.is_low_priority }
                        .thenByDescending { it.last_activity_time ?: 0L }
        )
        RoomSortOrder.NONE -> rows
    }
}
