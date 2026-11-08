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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.getStateEvent
import org.matrix.android.sdk.api.session.room.members.roomMemberQueryParams
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.session.room.powerlevels.RoomPowerLevels
import org.matrix.android.sdk.flow.flow
import org.matrix.android.sdk.flow.unwrap

class RoomKnockRequestsViewModel @AssistedInject constructor(
        @Assisted initialState: RoomKnockRequestsViewState,
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

        room.flow().liveRoomMembers(roomMemberQueryParams {
            displayName = QueryStringValue.NoCondition
            memberships = listOf(Membership.KNOCK)
        })
                .map { members ->
                    val memberEvents = loadMemberEvents(members)
                    setState {
                        copy(
                                reasons = memberEvents.mapValues { (_, event) ->
                                    event?.getClearContent().toModel<RoomMemberContent>()?.reason?.takeIf { it.isNotBlank() }
                                },
                                requestTimestamps = memberEvents.mapNotNull { (userId, event) ->
                                    event?.originServerTs?.let { userId to it }
                                }.toMap(),
                                handledRequests = handledRequests.filterTo(mutableSetOf()) { it in memberEvents },
                        )
                    }
                    members.map { member -> member.withKnockProfile(memberEvents[member.userId]) }
                }
                .flowOn(Dispatchers.IO)
                .execute {
                    copy(knockRequests = it)
                }

        // Seeded synchronously so the buttons are there on the first frame instead of popping in.
        setState { withPermissions(room.stateService().getRoomPowerLevels()) }
        room.flow().liveRoomPowerLevels()
                .setOnEach { withPermissions(it) }
    }

    private fun RoomKnockRequestsViewState.withPermissions(roomPowerLevels: RoomPowerLevels) = copy(
            canAccept = roomPowerLevels.isUserAbleToInvite(session.myUserId),
            canDecline = roomPowerLevels.isUserAbleToKick(session.myUserId),
    )

    // Member rows stored before knocks kept their own profile have no name or avatar; the knock event still does.
    private fun RoomMemberSummary.withKnockProfile(event: Event?): RoomMemberSummary {
        if (displayName != null && avatarUrl != null) return this
        val content = event?.getClearContent().toModel<RoomMemberContent>() ?: return this
        return copy(
                displayName = displayName ?: content.displayName,
                avatarUrl = avatarUrl ?: content.avatarUrl,
        )
    }

    private fun loadMemberEvents(members: List<RoomMemberSummary>): Map<String, Event?> {
        return members.associate { member ->
            member.userId to room.getStateEvent(EventType.STATE_ROOM_MEMBER, QueryStringValue.Equals(member.userId))
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
                // Hidden right away rather than when sync delivers the new membership.
                setState {
                    val stillListed = knockRequests()?.any { it.userId == roomMemberSummary.userId } == true
                    if (stillListed) copy(handledRequests = handledRequests + roomMemberSummary.userId) else this
                }
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
