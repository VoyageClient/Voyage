/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomdirectory.pendingrequests

import com.airbnb.mvrx.MavericksViewModelFactory
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.platform.EmptyViewEvents
import im.vector.app.core.platform.VectorViewModel
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.flow.flow

class PendingJoinRequestsViewModel @AssistedInject constructor(
        @Assisted initialState: PendingJoinRequestsViewState,
        private val session: Session
) : VectorViewModel<PendingJoinRequestsViewState, PendingJoinRequestsViewAction, EmptyViewEvents>(initialState) {

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<PendingJoinRequestsViewModel, PendingJoinRequestsViewState> {
        override fun create(initialState: PendingJoinRequestsViewState): PendingJoinRequestsViewModel
    }

    companion object : MavericksViewModelFactory<PendingJoinRequestsViewModel, PendingJoinRequestsViewState> by hiltMavericksViewModelFactory()

    init {
        session.flow()
                .liveRoomSummaries(roomSummaryQueryParams { memberships = listOf(Membership.KNOCK) })
                .execute {
                    copy(requests = it)
                }
    }

    override fun handle(action: PendingJoinRequestsViewAction) {
        when (action) {
            is PendingJoinRequestsViewAction.CancelRequest -> cancelRequest(action.roomId)
        }
    }

    private fun cancelRequest(roomId: String) {
        setState { copy(onGoingCancellation = onGoingCancellation + roomId) }
        viewModelScope.launch {
            try {
                // Retracting a knock is done by leaving the room (the spec-canonical method, which also
                // performs the federation make_leave/send_leave handshake for remote rooms).
                session.roomService().leaveRoom(roomId)
                setState { copy(cancelledRoomIds = cancelledRoomIds + roomId) }
            } catch (failure: Throwable) {
                // The room will simply remain in the list; surface nothing intrusive here.
            } finally {
                setState { copy(onGoingCancellation = onGoingCancellation - roomId) }
            }
        }
    }
}
