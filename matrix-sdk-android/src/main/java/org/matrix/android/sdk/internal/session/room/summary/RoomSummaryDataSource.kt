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

import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.matrix.android.sdk.api.query.RoomCategoryFilter
import org.matrix.android.sdk.api.query.SpaceFilter
import org.matrix.android.sdk.api.query.isNormalized
import org.matrix.android.sdk.api.session.room.RoomSortOrder
import org.matrix.android.sdk.api.session.room.RoomSummaryQueryParams
import org.matrix.android.sdk.api.session.room.model.LocalRoomSummary
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.RoomType
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.api.session.room.spaceSummaryQueryParams
import org.matrix.android.sdk.api.session.room.summary.RoomAggregateNotificationCount
import org.matrix.android.sdk.api.session.space.SpaceSummaryQueryParams
import org.matrix.android.sdk.api.util.MatrixPerf
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.database.mapper.LocalRoomSummaryMapper
import org.matrix.android.sdk.internal.database.mapper.RoomSummaryMapper
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.di.SessionDatabaseRead
import org.matrix.android.sdk.internal.query.matches
import org.matrix.android.sdk.internal.session.SessionScope
import javax.inject.Inject
import org.matrix.android.sdk.internal.database.sql.Room_summary as RoomSummaryRow

// Session-scoped: init registers a driver-level query listener that is never removed, and the
// snapshot/mapping caches only dedupe when shared. Unscoped, every DefaultRoom resolution leaked
// a listener, slowing every DB commit's notify pass as they accumulated.
@SessionScope
internal class RoomSummaryDataSource @Inject constructor(
        @SessionDatabase internal val database: SessionSqlDatabase,
        @SessionDatabaseRead internal val dispatcher: CoroutineDispatcher,
        private val roomSummaryMapper: RoomSummaryMapper,
        private val localRoomSummaryMapper: LocalRoomSummaryMapper,
        private val stores: SessionStores,
        private val previewInvalidation: RoomSummaryPreviewInvalidation,
) {
    internal val queries get() = database.roomSummaryQueries

    // The room list's sections each get their own paged list; on the default IO pool they load
    // concurrently and the smallest (usually Low priority) wins the race and briefly shows first. Loading
    // them on one thread makes them populate in the order they're observed (Favourites, Rooms/DMs, Low
    // priority) instead.
    internal val sectionFetchExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "room-list-section-paging")
    }

    // Mapping a row is expensive (deep latest-event/space/tag resolution). Memoize by the row itself
    // (a generated data class) so each sync only re-maps rooms whose summary actually changed; a new
    // message mutates the row (latest event id, unread counts, activity) → cache miss → fresh map.
    private val summaryCache = java.util.concurrent.ConcurrentHashMap<RoomSummaryRow, RoomSummary>()

    // One deserialized selectAll snapshot shared by every observer. The room list spins up 10+
    // selectAll-based observers (per-section paged lists, counts, notification counts); without this,
    // each re-ran its own full-table deserialize (~50-250ms for ~400 rooms) on every sync write.
    // The generation guard prevents caching a snapshot that a concurrent write already superseded.
    private val roomSummaryGeneration = MutableStateFlow(0L)
    @Volatile private var cachedAllRows: Pair<Long, List<RoomSummaryRow>>? = null
    private val allRowsInvalidator = Query.Listener {
        cachedAllRows = null
        roomSummaryGeneration.value += 1
    }

    // Retained so the driver-level listener registration lives as long as the (session-scoped) source.
    private val invalidationQuery = database.roomSummaryQueries.selectAll().also { it.addListener(allRowsInvalidator) }

    init {
        // Preview decryption changes the mapped summary without changing the row, so the row-keyed
        // memo must be evicted explicitly (see RoomSummaryPreviewInvalidation).
        previewInvalidation.register { roomId ->
            summaryCache.keys.removeAll { it.room_id == roomId }
        }
    }

    private fun allRows(): List<RoomSummaryRow> {
        cachedAllRows?.let { (gen, rows) -> if (gen == roomSummaryGeneration.value) return rows }
        val gen = roomSummaryGeneration.value
        val perfStart = MatrixPerf.now()
        val rows = queries.selectAll().executeAsList()
        MatrixPerf.end(perfStart) { "roomlist.selectAll rows=${rows.size}" }
        if (roomSummaryGeneration.value == gen) cachedAllRows = gen to rows
        return rows
    }

    /** Flow recomputing [transform] (against [allRows]) on the DB read dispatcher on every room_summary change. */
    internal fun <T> flowOnRoomSummaryChange(transform: () -> T): Flow<T> =
            roomSummaryGeneration.map { transform() }.flowOn(dispatcher)

    internal fun RoomSummaryRow.toDomain(): RoomSummary? {
        summaryCache[this]?.let { return it }
        val perfStart = MatrixPerf.now()
        val mapped = stores.roomSummary.get(room_id)?.let { roomSummaryMapper.map(it) } ?: return null
        MatrixPerf.end(perfStart) { "roomlist.toDomain.miss room=$room_id" }
        // Changed rooms leave their old row as a dead key; bound growth.
        if (summaryCache.size > 512) summaryCache.clear()
        summaryCache[this] = mapped
        return mapped
    }

    // Non-extension bridges so the android LiveData/PagedList extensions (RoomSummaryDataSourceAndroid)
    // can map rows without a cross-module member-extension call.
    internal fun rowToDomain(row: RoomSummaryRow): RoomSummary? = row.toDomain()

    internal fun filteredSortedSummaries(queryParams: RoomSummaryQueryParams, sortOrder: RoomSortOrder): List<RoomSummary> =
            filteredSortedRows(queryParams, sortOrder).mapNotNull { it.toDomain() }

    fun getRoomSummary(roomIdOrAlias: String): RoomSummary? {
        val row = if (roomIdOrAlias.startsWith("!")) {
            queries.selectByRoomId(roomIdOrAlias).executeAsOneOrNull()
        } else {
            queries.selectByAlias(roomIdOrAlias, roomIdOrAlias).executeAsOneOrNull()
        }
        return row?.toDomain()
    }

    fun getRoomSummaryFlow(roomId: String): Flow<Optional<RoomSummary>> {
        return queries.selectByRoomId(roomId).asFlow().mapToList(dispatcher)
                .map { rows -> rows.firstOrNull { !it.display_name.isNullOrEmpty() }?.toDomain().toOptional() }
    }

    fun getRoomSummaries(queryParams: RoomSummaryQueryParams, sortOrder: RoomSortOrder = RoomSortOrder.NONE): List<RoomSummary> {
        return filteredSortedRows(queryParams, sortOrder).mapNotNull { it.toDomain() }
    }

    fun getLocalRoomSummary(roomId: String): LocalRoomSummary? =
            stores.localRoomSummary.get(roomId)?.let { localRoomSummaryMapper.map(it) }

    fun getLocalRoomSummaryFlow(roomId: String): Flow<Optional<LocalRoomSummary>> {
        return database.localRoomSummaryQueries.selectByRoomId(roomId).asFlow().mapToList(dispatcher)
                .map { rows -> rows.firstOrNull()?.let { stores.localRoomSummary.get(it.room_id) }?.let { localRoomSummaryMapper.map(it) }.toOptional() }
    }

    fun getRoomSummariesFlow(queryParams: RoomSummaryQueryParams, sortOrder: RoomSortOrder = RoomSortOrder.NONE): Flow<List<RoomSummary>> =
            flowOnRoomSummaryChange { filteredSortedRows(queryParams, sortOrder).mapNotNull { row -> row.toDomain() } }

    fun getSpaceSummariesFlow(queryParams: SpaceSummaryQueryParams, sortOrder: RoomSortOrder = RoomSortOrder.NONE): Flow<List<RoomSummary>> =
            getRoomSummariesFlow(queryParams, sortOrder)

    fun getSpaceSummary(roomIdOrAlias: String): RoomSummary? = getRoomSummary(roomIdOrAlias)?.takeIf { it.roomType == RoomType.SPACE }

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

    fun getBreadcrumbsFlow(queryParams: RoomSummaryQueryParams): Flow<List<RoomSummary>> {
        return flowOnRoomSummaryChange {
            filteredSortedRows(queryParams, RoomSortOrder.NONE)
                    .filter { it.breadcrumbs_index > RoomSummary.NOT_IN_BREADCRUMBS }
                    .sortedBy { it.breadcrumbs_index }
                    .mapNotNull { it.toDomain() }
        }
    }

    fun getCountFlow(queryParams: RoomSummaryQueryParams): Flow<Int> {
        return flowOnRoomSummaryChange { filteredSortedRows(queryParams, RoomSortOrder.NONE).size }
    }

    fun getNotificationCountForRooms(queryParams: RoomSummaryQueryParams): RoomAggregateNotificationCount =
            MatrixPerf.time("roomlist.notificationCount") {
                val rows = filteredSortedRows(queryParams, RoomSortOrder.NONE)
                RoomAggregateNotificationCount(
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

    fun getFlattenOrphanRooms(): List<RoomSummary> {
        return getRoomSummaries(roomSummaryQueryParams {
            memberships = Membership.activeMemberships()
            excludeType = listOf(RoomType.SPACE)
            roomCategoryFilter = RoomCategoryFilter.ONLY_ROOMS
        }).filter { isOrphan(it) }
    }

    internal fun isOrphan(roomSummary: RoomSummary): Boolean {
        if (roomSummary.roomType == RoomType.SPACE && roomSummary.membership.isActive()) {
            return false
        }
        roomSummary.spaceParents?.forEach { info ->
            val parentSummary = info.roomSummary
            if (parentSummary != null && !parentSummary.membership.isLeft()) {
                if (!isOrphan(parentSummary)) return false
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

    internal fun filteredSortedRows(queryParams: RoomSummaryQueryParams, sortOrder: RoomSortOrder): List<RoomSummaryRow> {
        val all = allRows()
        val filterStart = MatrixPerf.now()
        return applyFilterAndSort(all, queryParams, sortOrder)
                .also { MatrixPerf.end(filterStart) { "roomlist.filterSort ${it.size}/${all.size} sort=$sortOrder" } }
    }

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
        p.removedFromRoom?.let { if ((is_removed_from_room != 0L) != it) return false }
        p.watched?.let { if ((is_watched != 0L) != it) return false }

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
