/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list.home

import android.content.SharedPreferences
import android.widget.ImageView
import androidx.paging.PagedList
import com.airbnb.mvrx.MavericksViewModelFactory
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.R
import im.vector.app.SpaceStateHandler
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.platform.StateView
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.resources.DrawableProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.home.room.list.home.header.HomeRoomFilter
import im.vector.app.features.room.LeaveRoomPrompt
import im.vector.app.features.room.getLeaveRoomWarning
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.spaces.tags.TagFilterStateHandler
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.query.RoomCategoryFilter
import org.matrix.android.sdk.api.query.RoomTagQueryFilter
import org.matrix.android.sdk.api.query.SpaceFilter
import org.matrix.android.sdk.api.query.toActiveSpaceOrNoFilter
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.getUserOrDefault
import org.matrix.android.sdk.api.session.room.RoomPagingService
import org.matrix.android.sdk.api.session.room.RoomSortOrder
import org.matrix.android.sdk.api.session.room.RoomSummaryQueryParams
import org.matrix.android.sdk.api.session.room.UpdatableLivePageResult
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.localecho.RoomLocalEcho
import org.matrix.android.sdk.api.session.room.model.tag.RoomTag
import org.matrix.android.sdk.api.session.room.read.ReadService
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toMatrixItem
import org.matrix.android.sdk.api.util.toOption
import org.matrix.android.sdk.flow.flow

class HomeRoomListViewModel @AssistedInject constructor(
        @Assisted initialState: HomeRoomListViewState,
        private val session: Session,
        private val spaceStateHandler: SpaceStateHandler,
        private val tagFilterStateHandler: TagFilterStateHandler,
        private val preferencesStore: HomeLayoutPreferencesStore,
        private val stringProvider: StringProvider,
        private val drawableProvider: DrawableProvider,
        private val vectorPreferences: VectorPreferences,
) : VectorViewModel<HomeRoomListViewState, HomeRoomListAction, HomeRoomListViewEvents>(initialState) {

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<HomeRoomListViewModel, HomeRoomListViewState> {
        override fun create(initialState: HomeRoomListViewState): HomeRoomListViewModel
    }

    companion object : MavericksViewModelFactory<HomeRoomListViewModel, HomeRoomListViewState> by hiltMavericksViewModelFactory()

    private val pagedListConfig = PagedList.Config.Builder()
            .setPageSize(30)
            .setInitialLoadSizeHint(60)
            .setEnablePlaceholders(true)
            .setPrefetchDistance(30)
            .build()

    val filteredPagedRoomSummariesLive: UpdatableLivePageResult by lazy {
        val builder = RoomSummaryQueryParams.Builder().also {
            it.memberships = listOf(Membership.JOIN)
            it.spaceFilter = spaceStateHandler.getCurrentSpace()?.roomId.toActiveSpaceOrNoFilter()
        }
        val params = getFilteredQueryParams(initialState.headersData.currentFilter, builder.build())
        val sortOrder = RoomSortOrder.ACTIVITY
        (session.roomService() as RoomPagingService).getFilteredPagedRoomSummariesLive(
                params,
                pagedListConfig,
                sortOrder
        )
    }

    private val directMemberLoadRequestedRoomIds = mutableSetOf<String>()

    // Realm-backed paged lists don't rebind on in-place field changes, so toggling the forced DM
    // display needs an explicit recompute + paged-list rebuild to reflect immediately.
    private val overrideDisplayPrefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == VectorPreferences.SETTINGS_OVERRIDE_DM_DISPLAY_KEY) {
            refreshRoomSummaryDisplayThenList()
        }
    }

    init {
        observeOrderPreferences()
        observeInvites()
        observeRecents()
        observeFilterTabs()
        observeSpaceChanges()
        observeDirectRoomsForMemberLoading()
        vectorPreferences.subscribeToChanges(overrideDisplayPrefListener)
    }

    override fun onCleared() {
        vectorPreferences.unsubscribeToChanges(overrideDisplayPrefListener)
        super.onCleared()
    }

    private fun refreshRoomSummaryDisplayThenList() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                session.roomService().refreshJoinedRoomSummaryDisplay(null)
            }
            // Rebuild the paged list so the recomputed names/avatars rebind.
            filteredPagedRoomSummariesLive.queryParams = filteredPagedRoomSummariesLive.queryParams
        }
    }

    // The forced DM display reads the direct user's per-room member event. When that member hasn't
    // been lazy-loaded yet, fetch the room member list (per-room API) so the name/avatar can resolve
    // without relying on the federated profile lookup, which may be disabled.
    private fun observeDirectRoomsForMemberLoading() {
        if (!vectorPreferences.overrideDirectChatDisplay()) return
        session.flow()
                .liveRoomSummaries(
                        roomSummaryQueryParams {
                            memberships = listOf(Membership.JOIN)
                        },
                        RoomSortOrder.ACTIVITY
                )
                .onEach { summaries ->
                    summaries.asSequence()
                            .filter { it.isDirect && it.directUserId != null }
                            .filter { directMemberLoadRequestedRoomIds.add(it.roomId) }
                            .forEach { summary -> loadDirectMemberIfMissing(summary.roomId, summary.directUserId!!) }
                }
                .launchIn(viewModelScope)
    }

    private fun loadDirectMemberIfMissing(roomId: String, directUserId: String) {
        viewModelScope.launch {
            val room = session.getRoom(roomId) ?: return@launch
            val member = room.membershipService().getRoomMember(directUserId)
            if (member?.displayName.isNullOrBlank() && member?.avatarUrl.isNullOrBlank()) {
                tryOrNull { room.membershipService().loadRoomMembersIfNeeded() }
            }
        }
    }

    private fun observeSpaceChanges() {
        combine(
                spaceStateHandler.getSelectedSpaceFlow()
                        .distinctUntilChanged()
                        .onStart { emit(spaceStateHandler.getCurrentSpace().toOption()) },
                tagFilterStateHandler.getSelectedTagFlow()
                        .distinctUntilChanged()
                        .onStart { emit(tagFilterStateHandler.getSelectedTag().toOption()) },
        ) { selectedSpaceOption, selectedTagOption ->
            selectedSpaceOption.orNull()?.roomId to selectedTagOption.orNull()
        }
                .distinctUntilChanged()
                .onEach { (selectedSpaceId, selectedTag) ->
                    updateEmptyState()
                    filteredPagedRoomSummariesLive.queryParams = filteredPagedRoomSummariesLive.queryParams.copy(
                            spaceFilter = if (selectedTag != null) {
                                SpaceFilter.NoFilter
                            } else {
                                selectedSpaceId.toActiveSpaceOrNoFilter()
                            },
                            activeTagFilter = selectedTag,
                    )
                }
                .launchIn(viewModelScope)
    }

    private fun observeInvites() {
        session.flow()
                .liveRoomSummaries(
                        roomSummaryQueryParams {
                            memberships = listOf(Membership.INVITE)
                        },
                        RoomSortOrder.ACTIVITY
                ).onEach { list ->
                    setState { copy(headersData = headersData.copy(invitesCount = list.size)) }
                }.launchIn(viewModelScope)
    }

    private fun observeRecents() {
        preferencesStore.areRecentsEnabledFlow
                .distinctUntilChanged()
                .flatMapLatest { areEnabled ->
                    if (areEnabled) {
                        session.flow()
                                .liveBreadcrumbs(roomSummaryQueryParams {
                                    memberships = listOf(Membership.JOIN)
                                })
                                .map { Optional.from(it) }
                    } else {
                        flowOf(Optional.empty())
                    }.onEach { listOptional ->
                        setState { copy(headersData = headersData.copy(recents = listOptional.getOrNull())) }
                    }
                }.launchIn(viewModelScope)
    }

    private fun observeFilterTabs() {
        preferencesStore.areFiltersEnabledFlow
                .distinctUntilChanged()
                .flatMapLatest { areEnabled ->
                    getFilterTabsFlow(areEnabled)
                }.onEach { filtersOptional ->
                    val filters = filtersOptional.getOrNull()
                    if (!isCurrentFilterStillValid(filters)) {
                        changeRoomFilter(HomeRoomFilter.ALL)
                    }
                    setState {
                        copy(
                                headersData = headersData.copy(
                                        filtersList = filters,
                                )
                        )
                    }
                }.launchIn(viewModelScope)
    }

    private suspend fun isCurrentFilterStillValid(filtersList: List<HomeRoomFilter>?): Boolean {
        if (filtersList.isNullOrEmpty()) return false
        val currentFilter = awaitState().headersData.currentFilter
        return filtersList.contains(currentFilter)
    }

    private fun getFilterTabsFlow(isEnabled: Boolean): Flow<Optional<MutableList<HomeRoomFilter>>> {
        if (!isEnabled) return flowOf(Optional.empty())
        val spaceFLow = spaceStateHandler.getSelectedSpaceFlow()
                .distinctUntilChanged()
                .onStart {
                    emit(spaceStateHandler.getCurrentSpace().toOption())
                }
        val favouritesFlow =
                spaceFLow.flatMapLatest { selectedSpace ->
                    session.flow()
                            .liveRoomSummaries(
                                    RoomSummaryQueryParams.Builder().also { builder ->
                                        builder.spaceFilter = selectedSpace.orNull()?.roomId.toActiveSpaceOrNoFilter()
                                        builder.roomTagQueryFilter = RoomTagQueryFilter(true, null, null)
                                    }.build()
                            )
                }
                        .map { it.isNotEmpty() }
                        .distinctUntilChanged()

        val dmsFLow =
                spaceFLow.flatMapLatest { selectedSpace ->
                    session.flow()
                            .liveRoomSummaries(
                                    RoomSummaryQueryParams.Builder().also { builder ->
                                        builder.spaceFilter = selectedSpace.orNull()?.roomId.toActiveSpaceOrNoFilter()
                                        builder.memberships = listOf(Membership.JOIN)
                                        builder.roomCategoryFilter = RoomCategoryFilter.ONLY_DM
                                    }.build()
                            )
                }
                        .map { it.isNotEmpty() }
                        .distinctUntilChanged()

        return combine(favouritesFlow, dmsFLow) { hasFavourite, hasDm ->
            hasFavourite to hasDm
        }.map { (hasFavourite, hasDm) ->
            val filtersData = mutableListOf(
                    HomeRoomFilter.ALL,
                    HomeRoomFilter.UNREADS
            )
            if (hasFavourite) {
                filtersData.add(
                        HomeRoomFilter.FAVOURITES
                )
            }
            if (hasDm) {
                filtersData.add(
                        HomeRoomFilter.PEOPlE
                )
            }
            Optional.from(filtersData)
        }
    }

    private fun observeOrderPreferences() {
        preferencesStore.isAZOrderingEnabledFlow
                .onEach { isAZOrdering ->
                    filteredPagedRoomSummariesLive.sortOrder = if (isAZOrdering) {
                        RoomSortOrder.NAME
                    } else {
                        RoomSortOrder.ACTIVITY
                    }
                }.launchIn(viewModelScope)
    }

    private suspend fun updateEmptyState() {
        val currentFilter = awaitState().headersData.currentFilter
        val emptyState = getEmptyStateData(currentFilter, spaceStateHandler.getCurrentSpace())
        setState { copy(emptyState = emptyState) }
    }

    private fun getFilteredQueryParams(filter: HomeRoomFilter, currentParams: RoomSummaryQueryParams): RoomSummaryQueryParams {
        return when (filter) {
            HomeRoomFilter.ALL -> currentParams.copy(
                    roomCategoryFilter = null,
                    roomTagQueryFilter = null
            )
            HomeRoomFilter.UNREADS -> currentParams.copy(
                    roomCategoryFilter = RoomCategoryFilter.ONLY_WITH_NOTIFICATIONS,
                    roomTagQueryFilter = RoomTagQueryFilter(null, false, null)
            )
            HomeRoomFilter.FAVOURITES ->
                currentParams.copy(
                        roomCategoryFilter = null,
                        roomTagQueryFilter = RoomTagQueryFilter(true, null, null)
                )
            HomeRoomFilter.PEOPlE -> currentParams.copy(
                    roomCategoryFilter = RoomCategoryFilter.ONLY_DM,
                    roomTagQueryFilter = null
            )
        }
    }

    private fun getEmptyStateData(filter: HomeRoomFilter, selectedSpace: RoomSummary?): StateView.State.Empty? {
        return when (filter) {
            HomeRoomFilter.ALL ->
                if (selectedSpace != null) {
                    StateView.State.Empty(
                            title = stringProvider.getString(CommonStrings.home_empty_space_no_rooms_title, selectedSpace.displayName),
                            message = stringProvider.getString(CommonStrings.home_empty_space_no_rooms_message),
                            image = drawableProvider.getDrawable(R.drawable.ill_empty_space),
                            isBigImage = true
                    )
                } else {
                    val userName = session.getUserOrDefault(session.myUserId).toMatrixItem().getBestName()
                    StateView.State.Empty(
                            title = stringProvider.getString(CommonStrings.home_empty_no_rooms_title, userName),
                            message = stringProvider.getString(CommonStrings.home_empty_no_rooms_message),
                            image = drawableProvider.getDrawable(R.drawable.ill_empty_all_chats),
                            isBigImage = true
                    )
                }
            HomeRoomFilter.UNREADS ->
                StateView.State.Empty(
                        title = stringProvider.getString(CommonStrings.home_empty_no_unreads_title),
                        message = stringProvider.getString(CommonStrings.home_empty_no_unreads_message),
                        image = drawableProvider.getDrawable(R.drawable.ill_empty_unreads),
                        isBigImage = true,
                        imageScaleType = ImageView.ScaleType.CENTER_INSIDE
                )
            else ->
                null
        }
    }

    override fun handle(action: HomeRoomListAction) {
        when (action) {
            is HomeRoomListAction.SelectRoom -> handleSelectRoom(action)
            is HomeRoomListAction.LeaveRoom -> handleLeaveRoom(action)
            is HomeRoomListAction.ChangeRoomNotificationState -> handleChangeNotificationMode(action)
            is HomeRoomListAction.ToggleTag -> handleToggleTag(action)
            is HomeRoomListAction.SetMarkedUnread -> handleSetMarkedUnread(action)
            is HomeRoomListAction.MarkRoomAsRead -> handleMarkRoomAsRead(action)
            is HomeRoomListAction.ChangeRoomFilter -> handleChangeRoomFilter(action.filter)
            HomeRoomListAction.DeleteAllLocalRoom -> handleDeleteLocalRooms()
        }
    }

    private fun handleChangeRoomFilter(newFilter: HomeRoomFilter) {
        viewModelScope.launch {
            changeRoomFilter(newFilter)
        }
    }

    private suspend fun changeRoomFilter(newFilter: HomeRoomFilter) {
        val currentFilter = awaitState().headersData.currentFilter
        if (currentFilter == newFilter) {
            return
        }
        setState { copy(headersData = headersData.copy(currentFilter = newFilter)) }
        updateEmptyState()
        filteredPagedRoomSummariesLive.queryParams = getFilteredQueryParams(newFilter, filteredPagedRoomSummariesLive.queryParams)
    }

    suspend fun getLeaveRoomWarning(roomId: String): LeaveRoomPrompt.Warning {
        return session.getLeaveRoomWarning(roomId)
    }

    private fun handleSelectRoom(action: HomeRoomListAction.SelectRoom) = withState {
        _viewEvents.post(HomeRoomListViewEvents.SelectRoom(action.roomSummary, false))
    }

    private fun handleLeaveRoom(action: HomeRoomListAction.LeaveRoom) {
        _viewEvents.post(HomeRoomListViewEvents.Loading(null))
        viewModelScope.launch {
            val value = runCatching { session.roomService().leaveRoom(action.roomId) }
                    .fold({ HomeRoomListViewEvents.Done }, { HomeRoomListViewEvents.Failure(it) })
            _viewEvents.post(value)
        }
    }

    private fun handleSetMarkedUnread(action: HomeRoomListAction.SetMarkedUnread) {
        viewModelScope.launch {
            try {
                session.getRoom(action.roomId)?.readService()?.setMarkedUnread(action.markedUnread)
            } catch (failure: Throwable) {
                _viewEvents.post(HomeRoomListViewEvents.Failure(failure))
            }
        }
    }

    private fun handleMarkRoomAsRead(action: HomeRoomListAction.MarkRoomAsRead) {
        viewModelScope.launch {
            try {
                // markAsRead also clears the MSC2867 marked-unread flag. Keep the receipt private unless
                // the user has read receipts enabled, mirroring room-open behaviour.
                session.getRoom(action.roomId)?.readService()?.markAsRead(
                        ReadService.MarkAsReadParams.BOTH,
                        mainTimeLineOnly = true,
                        public = vectorPreferences.sendReadReceipts(),
                )
            } catch (failure: Throwable) {
                _viewEvents.post(HomeRoomListViewEvents.Failure(failure))
            }
        }
    }

    private fun handleChangeNotificationMode(action: HomeRoomListAction.ChangeRoomNotificationState) {
        viewModelScope.launch {
            val room = session.getRoom(action.roomId)
            if (room != null) {
                try {
                    room.roomPushRuleService().setRoomNotificationState(action.notificationState)
                } catch (failure: Throwable) {
                    _viewEvents.post(HomeRoomListViewEvents.Failure(failure))
                }
            }
        }
    }

    private fun handleToggleTag(action: HomeRoomListAction.ToggleTag) {
        viewModelScope.launch {
            session.getRoom(action.roomId)?.let { room ->
                try {
                    if (room.roomSummary()?.hasTag(action.tag) == false) {
                        // Favorite and low priority tags are exclusive, so maybe delete the other tag first
                        action.tag.otherTag()
                                ?.takeIf { room.roomSummary()?.hasTag(it).orFalse() }
                                ?.let { tagToRemove ->
                                    room.tagsService().deleteTag(tagToRemove)
                                }

                        // Set the tag. We do not handle the order for the moment
                        room.tagsService().addTag(action.tag, 0.5)
                    } else {
                        room.tagsService().deleteTag(action.tag)
                    }
                } catch (failure: Throwable) {
                    _viewEvents.post(HomeRoomListViewEvents.Failure(failure))
                }
            }
        }
    }

    private fun handleDeleteLocalRooms() = withState {
        viewModelScope.launch {
            val localRoomIds = session.roomService()
                    .getRoomSummaries(roomSummaryQueryParams { roomId = QueryStringValue.Contains(RoomLocalEcho.PREFIX) })
                    .map { it.roomId }

            localRoomIds.forEach {
                session.roomService().deleteLocalRoom(it)
            }
        }
    }

    private fun String.otherTag(): String? {
        return when (this) {
            RoomTag.ROOM_TAG_FAVOURITE -> RoomTag.ROOM_TAG_LOW_PRIORITY
            RoomTag.ROOM_TAG_LOW_PRIORITY -> RoomTag.ROOM_TAG_FAVOURITE
            else -> null
        }
    }
}
