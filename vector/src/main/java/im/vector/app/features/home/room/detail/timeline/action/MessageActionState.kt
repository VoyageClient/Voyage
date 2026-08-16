/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.action

import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MavericksState
import com.airbnb.mvrx.Uninitialized
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent

/**
 * Quick reactions state.
 */
data class ToggleState(
        val reaction: String,
        val isSelected: Boolean
)

data class ActionPermissions(
        val canSendMessage: Boolean = false,
        val canReact: Boolean = false,
        val canRedact: Boolean = false,
        val canPinUnpin: Boolean = false
)

data class MessageActionState(
        val roomId: String,
        val eventId: String,
        val informationData: MessageInformationData,
        val timelineEvent: Async<TimelineEvent> = Uninitialized,
        // Set only when a redaction's content was put back. [timelineEvent] stays the redacted original,
        // because the sheet still has to know the message was deleted — which actions to offer, and how
        // to mark the header.
        val restoredEvent: TimelineEvent? = null,
        val messageBody: CharSequence = "",
        // For quick reactions
        val quickStates: Async<List<ToggleState>> = Uninitialized,
        // For actions
        val actions: List<EventSharedAction> = emptyList(),
        val actionPermissions: ActionPermissions = ActionPermissions(),
        val isFromThreadTimeline: Boolean = false,
        // MSC4268: display name of whoever shared the key to this message, if it came from a key bundle.
        val sharedByDisplayName: String? = null,
        // When set, the sheet is scoped to that item of an MSC4274 gallery.
        val galleryItemIndex: Int? = null,
) : MavericksState {

    /**
     * The event everything that renders the message's *content* should read: body, thumbnail, tables,
     * location, media-hiding. Restoring here rather than per-consumer is what keeps the sheet identical
     * to the timeline — a consumer that reaches for [timelineEvent] instead silently gets the pruned
     * content of a redacted event.
     */
    val previewEvent: TimelineEvent? get() = restoredEvent ?: timelineEvent()

    constructor(args: TimelineEventFragmentArgs) : this(
            roomId = args.roomId,
            eventId = args.eventId,
            informationData = args.informationData,
            isFromThreadTimeline = args.isFromThreadTimeline,
            galleryItemIndex = args.galleryItemIndex
    )

    fun senderName(): String = informationData.memberName?.toString() ?: ""

    // A reaction targets the event id, which a failed echo will never have server-side. A sheet
    // scoped to one gallery item offers no reactions either — those belong to the whole event.
    fun canReact() = timelineEvent() != null && actionPermissions.canReact &&
            timelineEvent()?.root?.sendState?.hasFailed() != true &&
            galleryItemIndex == null

    fun sendState(): SendState? = timelineEvent()?.root?.sendState
}
