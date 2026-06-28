/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list

import android.content.SharedPreferences
import androidx.lifecycle.MutableLiveData
import com.airbnb.mvrx.Async
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.Success
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.SpaceStateHandler
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.displayname.getBestName
import im.vector.app.features.invite.AutoAcceptInvites
import im.vector.app.features.room.LeaveRoomPrompt
import im.vector.app.features.room.getLeaveRoomWarning
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.spaces.tags.TagFilterStateHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.getRoomSummary
import org.matrix.android.sdk.api.session.room.UpdatableLivePageResult
import org.matrix.android.sdk.api.session.room.members.ChangeMembershipState
import org.matrix.android.sdk.api.session.room.model.localecho.RoomLocalEcho
import org.matrix.android.sdk.api.session.room.model.tag.RoomTag
import org.matrix.android.sdk.api.session.room.read.ReadService
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.api.util.toMatrixItem
import org.matrix.android.sdk.flow.flow
import timber.log.Timber

class RoomListViewModel @AssistedInject constructor(
        @Assisted initialState: RoomListViewState,
        private val session: Session,
        stringProvider: StringProvider,
        spaceStateHandler: SpaceStateHandler,
        tagFilterStateHandler: TagFilterStateHandler,
        private val vectorPreferences: VectorPreferences,
        autoAcceptInvites: AutoAcceptInvites,
) : VectorViewModel<RoomListViewState, RoomListAction, RoomListViewEvents>(initialState) {

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<RoomListViewModel, RoomListViewState> {
        override fun create(initialState: RoomListViewState): RoomListViewModel
    }

    private var updatableQuery: UpdatableLivePageResult? = null

    private val suggestedRoomJoiningState: MutableLiveData<Map<String, Async<Unit>>> = MutableLiveData(emptyMap())

    interface ActiveSpaceQueryUpdater {
        fun updateForSpaceId(roomId: String?, tag: String?)
    }

    enum class SpaceFilterStrategy {
        /**
         * Filter the rooms if they are part of the current space (children and grand children).
         * If current space is null, will return orphan rooms only
         */
        ORPHANS_IF_SPACE_NULL,

        /**
         * Special case when we don't want to discriminate rooms when current space is null.
         * In this case return all.
         */
        ALL_IF_SPACE_NULL,

        /** Do not filter based on space. */
        NONE
    }

    // Realm-backed paged lists don't rebind on in-place field changes, so toggling the forced DM
    // display needs an explicit recompute + section rebuild to reflect immediately.
    private val overrideDisplayPrefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == VectorPreferences.SETTINGS_OVERRIDE_DM_DISPLAY_KEY) {
            refreshRoomSummaryDisplayThenSections()
        }
    }

    init {
        observeMembershipChanges()
        vectorPreferences.subscribeToChanges(overrideDisplayPrefListener)

        spaceStateHandler.getSelectedSpaceFlow()
                .distinctUntilChanged()
                .execute {
                    copy(
                            asyncSelectedSpace = it.invoke()?.orNull()?.let { Success(it) } ?: Loading()
                    )
                }

        session.flow().liveUser(session.myUserId)
                .map { it.getOrNull()?.toMatrixItem()?.getBestName() }
                .distinctUntilChanged()
                .execute {
                    copy(
                            currentUserName = it.invoke() ?: session.myUserId
                    )
                }
    }

    private fun observeMembershipChanges() {
        session.flow()
                .liveRoomChangeMembershipState()
                .setOnEach {
                    copy(roomMembershipChanges = it)
                }
    }

    companion object : MavericksViewModelFactory<RoomListViewModel, RoomListViewState> by hiltMavericksViewModelFactory()

    private val roomListSectionBuilder = RoomListSectionBuilder(
            session,
            stringProvider,
            spaceStateHandler,
            tagFilterStateHandler,
            viewModelScope,
            autoAcceptInvites,
            {
                updatableQuery = it
            },
            suggestedRoomJoiningState,
            vectorPreferences,
            !vectorPreferences.prefSpacesShowAllRoomInHome()
    )

    val sections: List<RoomsSection> by lazy {
        roomListSectionBuilder.buildSections(initialState.displayMode)
    }

    override fun onCleared() {
        vectorPreferences.unsubscribeToChanges(overrideDisplayPrefListener)
        super.onCleared()
    }

    private fun refreshRoomSummaryDisplayThenSections() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                session.roomService().refreshJoinedRoomSummaryDisplay(null)
            }
            roomListSectionBuilder.refreshSections()
        }
    }

    override fun handle(action: RoomListAction) {
        when (action) {
            is RoomListAction.SelectRoom -> handleSelectRoom(action)
            is RoomListAction.AcceptInvitation -> handleAcceptInvitation(action)
            is RoomListAction.RejectInvitation -> handleRejectInvitation(action)
            is RoomListAction.FilterWith -> handleFilter(action)
            is RoomListAction.LeaveRoom -> handleLeaveRoom(action)
            is RoomListAction.ChangeRoomNotificationState -> handleChangeNotificationMode(action)
            is RoomListAction.ToggleTag -> handleToggleTag(action)
            is RoomListAction.SetMarkedUnread -> handleSetMarkedUnread(action)
            is RoomListAction.MarkRoomAsRead -> handleMarkRoomAsRead(action)
            is RoomListAction.ToggleSection -> handleToggleSection(action.section, action.persist)
            is RoomListAction.JoinSuggestedRoom -> handleJoinSuggestedRoom(action)
            is RoomListAction.ShowRoomDetails -> handleShowRoomDetails(action)
            RoomListAction.DeleteAllLocalRoom -> handleDeleteLocalRooms()
        }
    }

    suspend fun getLeaveRoomWarning(roomId: String): LeaveRoomPrompt.Warning {
        return session.getLeaveRoomWarning(roomId)
    }

    // PRIVATE METHODS *****************************************************************************

    private fun handleSelectRoom(action: RoomListAction.SelectRoom) = withState {
        _viewEvents.post(RoomListViewEvents.SelectRoom(action.roomSummary, false))
    }

    private fun handleToggleSection(roomSection: RoomsSection, persist: Boolean = true) {
        val nowExpanded = !roomSection.isExpanded.value.orFalse()
        roomSection.isExpanded.postValue(nowExpanded)
        if (persist) {
            vectorPreferences.setRoomSectionCollapsed(roomSection.collapseId, !nowExpanded)
        }
        /* TODO Cleanup if it is working
        sections.find { it.sectionName == roomSection.sectionName }
                ?.let { section ->
                    section.isExpanded.postValue(!section.isExpanded.value.orFalse())
                }
         */
    }

    private fun handleFilter(action: RoomListAction.FilterWith) {
        setState {
            copy(
                    roomFilter = action.filter
            )
        }
        updatableQuery?.apply {
            queryParams = queryParams.copy(
                    displayName = QueryStringValue.Contains(action.filter, QueryStringValue.Case.NORMALIZED)
            )
        }
    }

    private fun handleAcceptInvitation(action: RoomListAction.AcceptInvitation) = withState { state ->
        val roomId = action.roomSummary.roomId
        val roomMembershipChange = state.roomMembershipChanges[roomId]
        if (roomMembershipChange?.isInProgress().orFalse()) {
            // Request already sent, should not happen
            Timber.w("Try to join an already joining room. Should not happen")
            return@withState
        }
        _viewEvents.post(RoomListViewEvents.SelectRoom(action.roomSummary, true))

        // quick echo
        setState {
            copy(
                    roomMembershipChanges = roomMembershipChanges.mapValues {
                        if (it.key == roomId) {
                            ChangeMembershipState.Joining
                        } else {
                            it.value
                        }
                    }
            )
        }
    }

    private fun handleRejectInvitation(action: RoomListAction.RejectInvitation) = withState { state ->
        val roomId = action.roomSummary.roomId
        val roomMembershipChange = state.roomMembershipChanges[roomId]
        if (roomMembershipChange?.isInProgress().orFalse()) {
            // Request already sent, should not happen
            Timber.w("Try to left an already leaving or joining room. Should not happen")
            return@withState
        }

        viewModelScope.launch {
            try {
                session.roomService().leaveRoom(roomId)
                // We do not update the rejectingRoomsIds here, because, the room is not rejected yet regarding the sync data.
                // Instead, we wait for the room to be rejected
                // Known bug: if the user is invited again (after rejecting the first invitation), the loading will be displayed instead of the buttons.
                // If we update the state, the button will be displayed again, so it's not ideal...
            } catch (failure: Throwable) {
                // Notify the user
                _viewEvents.post(RoomListViewEvents.Failure(failure))
            }
        }
    }

    private fun handleChangeNotificationMode(action: RoomListAction.ChangeRoomNotificationState) {
        val room = session.getRoom(action.roomId)
        if (room != null) {
            viewModelScope.launch {
                try {
                    room.roomPushRuleService().setRoomNotificationState(action.notificationState)
                } catch (failure: Throwable) {
                    _viewEvents.post(RoomListViewEvents.Failure(failure))
                }
            }
        }
    }

    private fun handleJoinSuggestedRoom(action: RoomListAction.JoinSuggestedRoom) {
        suggestedRoomJoiningState.postValue(suggestedRoomJoiningState.value.orEmpty().toMutableMap().apply {
            this[action.roomId] = Loading()
        }.toMap())

        viewModelScope.launch {
            try {
                session.roomService().joinRoom(action.roomId, null, action.viaServers ?: emptyList())

                suggestedRoomJoiningState.postValue(suggestedRoomJoiningState.value.orEmpty().toMutableMap().apply {
                    this[action.roomId] = Success(Unit)
                }.toMap())
            } catch (failure: Throwable) {
                suggestedRoomJoiningState.postValue(suggestedRoomJoiningState.value.orEmpty().toMutableMap().apply {
                    this[action.roomId] = Fail(failure)
                }.toMap())
            }
            session.getRoomSummary(action.roomId)
        }
    }

    private fun handleShowRoomDetails(action: RoomListAction.ShowRoomDetails) {
        session.permalinkService().createRoomPermalink(action.roomId, action.viaServers)?.let {
            _viewEvents.post(RoomListViewEvents.NavigateToMxToBottomSheet(it))
        }
    }

    private fun handleToggleTag(action: RoomListAction.ToggleTag) {
        session.getRoom(action.roomId)?.let { room ->
            viewModelScope.launch(Dispatchers.IO) {
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
                    _viewEvents.post(RoomListViewEvents.Failure(failure))
                }
            }
        }
    }

    private fun handleSetMarkedUnread(action: RoomListAction.SetMarkedUnread) {
        session.getRoom(action.roomId)?.let { room ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    room.readService().setMarkedUnread(action.markedUnread)
                } catch (failure: Throwable) {
                    _viewEvents.post(RoomListViewEvents.Failure(failure))
                }
            }
        }
    }

    private fun handleMarkRoomAsRead(action: RoomListAction.MarkRoomAsRead) {
        session.getRoom(action.roomId)?.let { room ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    // markAsRead also clears the MSC2867 marked-unread flag. Mirror room-open behaviour:
                    // only send a public receipt when the user has read receipts enabled, else keep it private.
                    room.readService().markAsRead(
                            ReadService.MarkAsReadParams.BOTH,
                            mainTimeLineOnly = true,
                            public = vectorPreferences.sendReadReceipts(),
                    )
                } catch (failure: Throwable) {
                    _viewEvents.post(RoomListViewEvents.Failure(failure))
                }
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

    private fun handleLeaveRoom(action: RoomListAction.LeaveRoom) {
        _viewEvents.post(RoomListViewEvents.Loading(null))
        viewModelScope.launch {
            val value = runCatching { session.roomService().leaveRoom(action.roomId) }
                    .fold({ RoomListViewEvents.Done }, { RoomListViewEvents.Failure(it) })
            _viewEvents.post(value)
        }
    }

    private fun handleDeleteLocalRooms() {
        viewModelScope.launch(session.coroutineDispatchers.io) {
            val localRoomIds = session.roomService()
                    .getRoomSummaries(roomSummaryQueryParams { roomId = QueryStringValue.Contains(RoomLocalEcho.PREFIX) })
                    .map { it.roomId }
            localRoomIds.forEach {
                session.roomService().deleteLocalRoom(it)
            }
        }
    }
}
