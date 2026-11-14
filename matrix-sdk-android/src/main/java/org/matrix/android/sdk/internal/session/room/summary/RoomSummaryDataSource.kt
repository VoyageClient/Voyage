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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.matrix.android.sdk.api.query.QueryStringValue
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
import org.matrix.android.sdk.internal.session.sync.SyncImportState
import javax.inject.Inject
import org.matrix.android.sdk.internal.database.sql.Room_summary as RoomSummaryRow

// Room instances must share query listeners and snapshot caches.
@SessionScope
internal class RoomSummaryDataSource @Inject constructor(
        @SessionDatabase internal val database: SessionSqlDatabase,
        @SessionDatabaseRead internal val dispatcher: CoroutineDispatcher,
        private val roomSummaryMapper: RoomSummaryMapper,
        private val localRoomSummaryMapper: LocalRoomSummaryMapper,
        private val stores: SessionStores,
        private val previewInvalidation: RoomSummaryPreviewInvalidation,
        private val syncImportState: SyncImportState,
) {
    internal val queries get() = database.roomSummaryQueries

    // Serialize section loads so small sections cannot appear ahead of higher-priority ones.
    internal val sectionFetchExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "room-list-section-paging")
    }

    // Keep one mapping per room; keying by full row would retain every revision.
    private val summaryCache = java.util.concurrent.ConcurrentHashMap<String, Pair<RoomSummaryRow, RoomSummary>>()

    // Share selectAll across observers; generations reject snapshots superseded by concurrent writes.
    private val roomSummaryGeneration = MutableStateFlow(0L)
    @Volatile private var cachedAllRows: Pair<Long, List<RoomSummaryRow>>? = null
    private val allRowsLock = Any()
    private val cachedFilteredRows = java.util.concurrent.ConcurrentHashMap<Pair<RoomSummaryQueryParams, RoomSortOrder>,
            Pair<Long, List<RoomSummaryRow>>>()
    private val cachedCounts = java.util.concurrent.ConcurrentHashMap<RoomSummaryQueryParams,
            Pair<Long, RoomAggregateNotificationCount>>()
    private val allRowsInvalidator = Query.Listener {
        cachedAllRows = null
        cachedFilteredRows.clear()
        cachedCounts.clear()
        roomSummaryGeneration.value += 1
    }

    private val invalidationQuery = database.roomSummaryQueries.selectAll().also { it.addListener(allRowsInvalidator) }

    // Tag-only changes must invalidate filters even when summary rows stay unchanged.
    private val tagInvalidator = Query.Listener { allRowsInvalidator.queryResultsChanged() }
    private val tagInvalidationQuery = database.roomTagQueries.selectRoomIdsByTag("").also { it.addListener(tagInvalidator) }

    init {
        // Decryption can change a preview without changing its summary row.
        previewInvalidation.register { roomId -> summaryCache.remove(roomId) }
    }

    private fun allRows(): List<RoomSummaryRow> {
        cachedAllRows?.let { (gen, rows) -> if (gen == roomSummaryGeneration.value) return rows }
        // Observers share one table read per generation.
        return synchronized(allRowsLock) {
            cachedAllRows?.let { (gen, rows) -> if (gen == roomSummaryGeneration.value) return rows }
            val gen = roomSummaryGeneration.value
            val perfStart = MatrixPerf.now()
            val rows = queries.selectAll().executeAsList()
            MatrixPerf.end(perfStart) { "roomlist.selectAll rows=${rows.size}" }
            if (roomSummaryGeneration.value == gen) cachedAllRows = gen to rows
            rows
        }
    }

    // Pace updates during imports without starving observers.
    private val roomSummaryChange: Flow<Long> = flow {
        var emitted = -1L
        roomSummaryGeneration.collect { generation ->
            if (generation == emitted) return@collect
            emitted = generation
            emit(generation)
            delay(if (syncImportState.isImporting) IMPORT_COALESCE_MS else CHANGE_COALESCE_MS)
        }
    }

    /** Emits (without data) once per room_summary write burst, for observers that recompute their own view. */
    fun getRoomSummaryUpdateFlow(): Flow<Unit> = roomSummaryChange.map { }

    /** Flow recomputing [transform] (against [allRows]) on the DB read dispatcher on every room_summary change. */
    internal fun <T> flowOnRoomSummaryChange(transform: () -> T): Flow<T> =
            roomSummaryChange.map { transform() }.flowOn(dispatcher)

    internal fun RoomSummaryRow.toDomain(): RoomSummary? {
        summaryCache[room_id]?.let { cached ->
            val (row, mapped) = cached
            if (row === this) return mapped
            if (row == this) {
                summaryCache.replace(room_id, cached, this to mapped)
                return mapped
            }
        }
        val perfStart = MatrixPerf.now()
        val mapped = stores.roomSummary.get(room_id)?.let { roomSummaryMapper.map(it) } ?: return null
        MatrixPerf.end(perfStart) { "roomlist.toDomain.miss room=$room_id" }
        summaryCache[room_id] = this to mapped
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
                .distinctUntilChanged { old, new -> old.orNull() === new.orNull() }
                .flowOn(dispatcher)
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
                    .distinctUntilChanged { old, new -> old.size == new.size && old.indices.all { old[it] === new[it] } }

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
        }.distinctUntilChanged { old, new -> old.size == new.size && old.indices.all { old[it] === new[it] } }
    }

    fun getCountFlow(queryParams: RoomSummaryQueryParams): Flow<Int> {
        return flowOnRoomSummaryChange { getRoomSummariesCount(queryParams) }
    }

    /** How many rooms match, without mapping any of them — `getRoomSummaries(...).size` maps every one. */
    fun getRoomSummariesCount(queryParams: RoomSummaryQueryParams): Int =
            filteredSortedRows(queryParams, RoomSortOrder.NONE).size

    // Counts and room-list sections share the same row snapshot and generation.
    fun getNotificationCountForRooms(queryParams: RoomSummaryQueryParams): RoomAggregateNotificationCount =
            MatrixPerf.time("roomlist.notificationCount") {
                val generation = roomSummaryGeneration.value
                cachedCounts[queryParams]?.let { (gen, counts) -> if (gen == generation) return@time counts }
                val rows = filteredSortedRows(queryParams, RoomSortOrder.NONE)
                var notificationCount = 0
                var highlightCount = 0
                rows.forEach {
                    notificationCount += it.notification_count.toInt()
                    highlightCount += it.highlight_count.toInt()
                }
                RoomAggregateNotificationCount(notificationCount, highlightCount).also {
                    if (roomSummaryGeneration.value == generation) cachedCounts[queryParams] = generation to it
                }
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
        val generation = roomSummaryGeneration.value
        val all = allRows()
        val key = queryParams to sortOrder
        cachedFilteredRows[key]?.let { (gen, rows) -> if (gen == generation) return rows }
        val filterStart = MatrixPerf.now()
        return applyFilterAndSort(all, queryParams, sortOrder)
                .also { rows ->
                    MatrixPerf.end(filterStart) { "roomlist.filterSort ${rows.size}/${all.size} sort=$sortOrder" }
                    if (roomSummaryGeneration.value == generation) cachedFilteredRows[key] = generation to rows
                }
    }

    private fun applyFilterAndSort(rows: List<RoomSummaryRow>, queryParams: RoomSummaryQueryParams, sortOrder: RoomSortOrder): List<RoomSummaryRow> {
        // Resolved once per pass: a per-row lookup here was one DB query per room, on every pass.
        val taggedRoomIds = queryParams.activeTagFilter?.let { database.roomTagQueries.selectRoomIdsByTag(it).executeAsList().toHashSet() }
        val hasTagRoomIds = queryParams.hasTag?.let { database.roomTagQueries.selectRoomIdsByTag(it).executeAsList().toHashSet() }
        val excludedTagRoomIds = queryParams.excludeTags.takeIf { it.isNotEmpty() }
                ?.flatMapTo(HashSet()) { database.roomTagQueries.selectRoomIdsByTag(it).executeAsList() }
        val filter = CompiledRoomFilter(queryParams, taggedRoomIds, hasTagRoomIds, excludedTagRoomIds)
        return sort(rows.filter { filter.matches(it) }, sortOrder)
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

    private companion object {
        const val CHANGE_COALESCE_MS = 120L

        // Enough to stop a fill's write stream from driving a list pass per commit, while still growing the
        // list visibly as rooms land. (Traced: the import is not waiting on these passes, so backing them
        // off further buys nothing and only makes the room list lag the data.)
        const val IMPORT_COALESCE_MS = 250L
    }
}

/**
 * The room-list filter, with everything that does not depend on the row resolved up front. It is applied
 * to every room in the account, for every section, on every change tick — so a per-row allocation here
 * (the membership names were rebuilt into a list per row) costs more than the comparison it feeds.
 */
internal class CompiledRoomFilter(
        private val p: RoomSummaryQueryParams,
        private val taggedRoomIds: Set<String>?,
        private val hasTagRoomIds: Set<String>?,
        private val excludedTagRoomIds: Set<String>?,
) {
    private val membershipNames: Set<String>? = p.memberships.takeIf { it.isNotEmpty() }?.mapTo(HashSet()) { it.name }
    private val excludeTypes: Set<String?>? = p.excludeType?.toHashSet()
    private val includeTypes: Set<String?>? = p.includeType?.toHashSet()
    private val matchNormalizedName = p.displayName.isNormalized()
    private val filtersRoomId = p.roomId !is QueryStringValue.NoCondition
    private val filtersDisplayName = p.displayName !is QueryStringValue.NoCondition
    private val filtersAlias = p.canonicalAlias !is QueryStringValue.NoCondition

    fun matches(row: RoomSummaryRow): Boolean = with(row) {
        if (room_id.isEmpty()) return false
        if (filtersRoomId && !p.roomId.matches(room_id)) return false
        if (filtersDisplayName) {
            val displayNameField = if (matchNormalizedName) normalized_display_name else display_name
            if (!p.displayName.matches(displayNameField)) return false
        }
        if (filtersAlias && !p.canonicalAlias.matches(canonical_alias)) return false
        if (membershipNames != null && membership_str !in membershipNames) return false
        if (is_hidden_from_user != 0L) return false
        p.removedFromRoom?.let { if ((is_removed_from_room != 0L) != it) return false }
        p.watched?.let { if ((is_watched != 0L) != it) return false }

        p.roomTagQueryFilter?.let { f ->
            f.isFavorite?.let { if ((is_favourite != 0L) != it) return false }
            f.isLowPriority?.let { if ((is_low_priority != 0L) != it) return false }
            f.isServerNotice?.let { if ((is_server_notice != 0L) != it) return false }
        }
        excludeTypes?.let { if (room_type in it) return false }
        includeTypes?.let { if (room_type !in it) return false }
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
        if (p.activeTagFilter != null && room_id !in taggedRoomIds.orEmpty()) return false
        if (p.hasTag != null && room_id !in hasTagRoomIds.orEmpty()) return false
        if (excludedTagRoomIds != null && room_id in excludedTagRoomIds) return false
        return true
    }
}

/** The filter predicate, for callers holding a single row. */
internal fun RoomSummaryRow.matches(
        p: RoomSummaryQueryParams,
        taggedRoomIds: Set<String>?,
        hasTagRoomIds: Set<String>?,
        excludedTagRoomIds: Set<String>?,
): Boolean = CompiledRoomFilter(p, taggedRoomIds, hasTagRoomIds, excludedTagRoomIds).matches(this)
