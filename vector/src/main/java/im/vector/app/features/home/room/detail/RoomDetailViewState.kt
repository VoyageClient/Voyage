/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail

import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MavericksState
import com.airbnb.mvrx.Uninitialized
import im.vector.app.features.home.room.detail.arguments.TimelineArgs
import im.vector.app.features.redaction.MassRedactionState
import im.vector.app.features.share.SharedData
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.room.members.ChangeMembershipState
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.localecho.RoomLocalEcho
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.sync.SyncRequestState
import org.matrix.android.sdk.api.session.sync.SyncState
import org.matrix.android.sdk.api.session.threads.ThreadNotificationBadgeState
import org.matrix.android.sdk.api.session.widgets.model.Widget

sealed class UnreadState {
    object Unknown : UnreadState()
    object HasNoUnread : UnreadState()
    data class ReadMarkerNotLoaded(val readMarkerId: String) : UnreadState()
    data class HasUnread(val firstUnreadEventId: String, val readMarkerId: String) : UnreadState()
}

data class RoomDetailViewState(
        val roomId: String,
        val eventId: String?,
        val isInviteAlreadyAccepted: Boolean,
        val myRoomMember: Async<RoomMemberSummary> = Uninitialized,
        val asyncInviter: Async<RoomMemberSummary> = Uninitialized,
        val asyncRoomSummary: Async<RoomSummary> = Uninitialized,
        val activeRoomWidgets: Async<List<Widget>> = Uninitialized,
        val formattedTypingUsers: String? = null,
        val tombstoneEvent: Event? = null,
        val joinUpgradedRoomAsync: Async<String> = Uninitialized,
        val syncState: SyncState? = null,
        val incrementalSyncRequestState: SyncRequestState.IncrementalSyncRequestState? = null,
        val pushCounter: Int = 0,
        val highlightedEventId: String? = null,
        val unreadState: UnreadState = UnreadState.Unknown,
        val canShowJumpToReadMarker: Boolean = true,
        val changeMembershipState: ChangeMembershipState = ChangeMembershipState.Unknown,
        val canInvite: Boolean = true,
        val isAllowedToManageWidgets: Boolean = false,
        val isAllowedToSetupEncryption: Boolean = true,
        val hasFailedSending: Boolean = false,
        val switchToParentSpace: Boolean = false,
        val rootThreadEventId: String? = null,
        val threadNotificationBadgeState: ThreadNotificationBadgeState = ThreadNotificationBadgeState(),
        val typingUsers: List<SenderInfo>? = null,
        val isSharingLiveLocation: Boolean = false,
        val showKeyboardWhenPresented: Boolean = false,
        val sharedData: SharedData? = null,
        val pinnedEvents: List<TimelineEvent> = emptyList(),
        val massRedactionState: MassRedactionState? = null,
) : MavericksState {

    constructor(args: TimelineArgs) : this(
            roomId = args.roomId,
            eventId = args.eventId,
            isInviteAlreadyAccepted = args.isInviteAlreadyAccepted,
            // Also highlight the target event, if any
            highlightedEventId = args.eventId,
            switchToParentSpace = args.switchToParentSpace,
            rootThreadEventId = args.threadTimelineArgs?.rootThreadEventId,
            showKeyboardWhenPresented = args.threadTimelineArgs?.showKeyboard.orFalse(),
            sharedData = args.sharedData,
    )

    fun isSearchAvailable(searchEncryptedRoomsEnabled: Boolean = false) =
            asyncRoomSummary()?.let { !it.isEncrypted || searchEncryptedRoomsEnabled } == true

    fun isDm() = asyncRoomSummary()?.isDirect == true

    fun isThreadTimeline() = rootThreadEventId != null

    fun isLocalRoom() = RoomLocalEcho.isLocalEchoId(roomId)
}
