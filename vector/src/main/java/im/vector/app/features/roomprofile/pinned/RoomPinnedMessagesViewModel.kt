/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.pinned

import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.Success
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.features.home.room.detail.pinned.GetPinnedEventsUseCase
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.model.RoomPinnedEventsContent
import org.matrix.android.sdk.flow.flow

class RoomPinnedMessagesViewModel @AssistedInject constructor(
        @Assisted initialState: RoomPinnedMessagesViewState,
        private val session: Session,
        private val getPinnedEventsUseCase: GetPinnedEventsUseCase,
) : VectorViewModel<RoomPinnedMessagesViewState, RoomPinnedMessagesAction, RoomPinnedMessagesViewEvents>(initialState) {

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<RoomPinnedMessagesViewModel, RoomPinnedMessagesViewState> {
        override fun create(initialState: RoomPinnedMessagesViewState): RoomPinnedMessagesViewModel
    }

    companion object : MavericksViewModelFactory<RoomPinnedMessagesViewModel, RoomPinnedMessagesViewState> by hiltMavericksViewModelFactory()

    private val room = session.getRoom(initialState.roomId)!!

    init {
        observePinnedEvents()
        observePowerLevels()
    }

    private fun observePinnedEvents() {
        setState { copy(pinnedEvents = Loading()) }
        getPinnedEventsUseCase.execute(room)
                .onEach { events ->
                    // The use case yields oldest-first (for the timeline banner); show newest-first here.
                    setState { copy(pinnedEvents = Success(events.reversed()), pinnedEventsTick = pinnedEventsTick + 1) }
                }
                .launchIn(viewModelScope)
    }

    private fun observePowerLevels() {
        room.flow().liveRoomPowerLevels()
                .onEach { powerLevels ->
                    val canEdit = powerLevels.isUserAllowedToSend(session.myUserId, true, EventType.STATE_ROOM_PINNED_EVENT)
                    setState { copy(canEditPinnedEvents = canEdit) }
                }
                .launchIn(viewModelScope)
    }

    override fun handle(action: RoomPinnedMessagesAction) {
        when (action) {
            is RoomPinnedMessagesAction.Unpin -> handleUnpin(action.eventId)
        }
    }

    private fun handleUnpin(eventId: String) {
        viewModelScope.launch {
            try {
                val current = room.stateService()
                        .getStateEvent(EventType.STATE_ROOM_PINNED_EVENT, QueryStringValue.IsEmpty)
                        ?.content
                        .toModel<RoomPinnedEventsContent>()
                        ?.pinned
                        .orEmpty()
                val updated = current - eventId
                if (updated != current) {
                    room.stateService().sendStateEvent(
                            eventType = EventType.STATE_ROOM_PINNED_EVENT,
                            stateKey = "",
                            body = RoomPinnedEventsContent(pinned = updated).toContent()
                    )
                }
            } catch (failure: Throwable) {
                _viewEvents.post(RoomPinnedMessagesViewEvents.Failure(failure))
            }
        }
    }
}
