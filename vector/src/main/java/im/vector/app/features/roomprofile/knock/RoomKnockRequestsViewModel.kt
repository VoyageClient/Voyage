/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.knock

import com.airbnb.mvrx.MavericksViewModelFactory
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.displayname.getBestName
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.getStateEvent
import org.matrix.android.sdk.api.session.room.members.roomMemberQueryParams
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.util.toMatrixItem
import org.matrix.android.sdk.flow.flow
import org.matrix.android.sdk.flow.unwrap

class RoomKnockRequestsViewModel @AssistedInject constructor(
        @Assisted initialState: RoomKnockRequestsViewState,
        private val stringProvider: StringProvider,
        private val session: Session
) : VectorViewModel<RoomKnockRequestsViewState, RoomKnockRequestsAction, RoomKnockRequestsViewEvents>(initialState) {

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<RoomKnockRequestsViewModel, RoomKnockRequestsViewState> {
        override fun create(initialState: RoomKnockRequestsViewState): RoomKnockRequestsViewModel
    }

    private val room = session.getRoom(initialState.roomId)!!

    init {
        room.flow().liveRoomSummary()
                .unwrap()
                .execute { async ->
                    copy(roomSummary = async)
                }

        room.flow().liveRoomMembers(roomMemberQueryParams { memberships = listOf(Membership.KNOCK) })
                .execute {
                    copy(
                            knockRequests = it,
                            reasons = it.invoke()?.let { members -> loadReasons(members) } ?: reasons
                    )
                }

        room.flow().liveRoomPowerLevels()
                .setOnEach { roomPowerLevels ->
                    copy(canModerate = roomPowerLevels.isUserAbleToInvite(session.myUserId) && roomPowerLevels.isUserAbleToKick(session.myUserId))
                }
    }

    private fun loadReasons(members: List<RoomMemberSummary>): Map<String, String?> {
        return members.associate { member ->
            val content = room.getStateEvent(EventType.STATE_ROOM_MEMBER, QueryStringValue.Equals(member.userId))
                    ?.getClearContent()
                    .toModel<RoomMemberContent>()
            member.userId to content?.reason?.takeIf { it.isNotBlank() }
        }
    }

    companion object : MavericksViewModelFactory<RoomKnockRequestsViewModel, RoomKnockRequestsViewState> by hiltMavericksViewModelFactory()

    override fun handle(action: RoomKnockRequestsAction) {
        when (action) {
            is RoomKnockRequestsAction.Accept -> acceptRequest(action.roomMemberSummary)
            is RoomKnockRequestsAction.Decline -> declineRequest(action.roomMemberSummary)
        }
    }

    private fun acceptRequest(roomMemberSummary: RoomMemberSummary) {
        moderate(roomMemberSummary, accept = true)
    }

    private fun declineRequest(roomMemberSummary: RoomMemberSummary) {
        moderate(roomMemberSummary, accept = false)
    }

    private fun moderate(roomMemberSummary: RoomMemberSummary, accept: Boolean) {
        setState {
            copy(onGoingModerationAction = onGoingModerationAction + roomMemberSummary.userId)
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (accept) {
                    room.membershipService().invite(roomMemberSummary.userId)
                } else {
                    room.membershipService().kick(roomMemberSummary.userId)
                }
                val message = stringProvider.getString(
                        if (accept) CommonStrings.room_knock_request_accepted else CommonStrings.room_knock_request_declined,
                        roomMemberSummary.toMatrixItem().getBestName()
                )
                _viewEvents.post(RoomKnockRequestsViewEvents.ToastMessage(message))
            } catch (failure: Throwable) {
                _viewEvents.post(RoomKnockRequestsViewEvents.ToastMessage(failure.localizedMessage ?: ""))
            } finally {
                setState {
                    copy(onGoingModerationAction = onGoingModerationAction - roomMemberSummary.userId)
                }
            }
        }
    }
}
