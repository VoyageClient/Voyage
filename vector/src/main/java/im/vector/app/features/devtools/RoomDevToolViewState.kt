/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.devtools

import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MavericksState
import com.airbnb.mvrx.Uninitialized
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.accountdata.RoomAccountDataEvent

data class RoomDevToolViewState(
        val roomId: String = "",
        val displayMode: Mode = Mode.Root,
        val stateEvents: Async<List<Event>> = Uninitialized,
        val roomAccountData: Async<List<RoomAccountDataEvent>> = Uninitialized,
        val currentStateType: String? = null,
        val selectedEvent: Event? = null,
        val selectedAccountData: RoomAccountDataEvent? = null,
        val selectedEventJson: String? = null,
        val editedContent: String? = null,
        /** False while previewing an un-joined room: state browsing works, sending/editing cannot.
         * Defaults false so preview mode never flashes the edit affordances before the VM decides. */
        val canEditState: Boolean = false,
        val modalLoading: Async<Unit> = Uninitialized,
        val sendEventDraft: SendEventDraft? = null,
        /** True when opened straight onto a send form (/sendevent, /sendstate): there is no Root to back out to. */
        val sendFormIsRoot: Boolean = false
) : MavericksState {

    constructor(args: RoomDevToolActivity.Args) : this(
            roomId = args.roomId,
            displayMode = args.sendTarget?.let { Mode.SendEventForm(it) } ?: Mode.Root,
            sendEventDraft = args.sendTarget?.let { SendEventDraft(defaultTypeFor(it), null, "{\n}") },
            sendFormIsRoot = args.sendTarget != null
    )

    sealed class Mode {
        object Root : Mode()
        object StateEventList : Mode()
        object StateEventListByType : Mode()
        object StateEventDetail : Mode()
        object AccountDataList : Mode()
        object AccountDataDetail : Mode()
        object EditEventContent : Mode()
        data class SendEventForm(val target: SendTarget) : Mode()
    }

    enum class SendTarget { MESSAGE, STATE, ACCOUNT_DATA }

    companion object {
        fun defaultTypeFor(target: SendTarget) = if (target == SendTarget.ACCOUNT_DATA) null else EventType.MESSAGE
    }

    data class SendEventDraft(
            val type: String?,
            val stateKey: String?,
            val content: String?
    )
}
