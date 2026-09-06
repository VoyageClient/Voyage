/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roomprofile.acl

import com.airbnb.mvrx.MavericksViewModelFactory
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.platform.VectorViewEvents
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.platform.VectorViewModelAction
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.model.RoomServerAclContent
import org.matrix.android.sdk.flow.flow
import org.matrix.android.sdk.flow.unwrap

class RoomAclViewModel @AssistedInject constructor(
        @Assisted initialState: RoomAclViewState,
        private val session: Session
) : VectorViewModel<RoomAclViewState, RoomAclAction, RoomAclEvent>(initialState) {
    @AssistedFactory interface Factory : MavericksAssistedViewModelFactory<RoomAclViewModel, RoomAclViewState> {
        override fun create(initialState: RoomAclViewState): RoomAclViewModel
    }
    companion object : MavericksViewModelFactory<RoomAclViewModel, RoomAclViewState> by hiltMavericksViewModelFactory()

    private val room = session.getRoom(initialState.roomId)!!
    private var nextId = 0L
    private var hasLoadedAcl = false
    private var initialDraft: RoomAclDraft? = null
    private var draftEntries = emptyList<RoomAclEntry>()
    private var draftAllowIpLiterals = true

    fun hasUnsavedChanges(): Boolean = initialDraft != null && initialDraft != roomAclDraft(draftEntries, draftAllowIpLiterals)

    init {
        room.flow().liveRoomSummary().unwrap().execute { copy(roomSummary = it) }
        room.flow().liveStateEvent(EventType.STATE_ROOM_SERVER_ACL, QueryStringValue.IsEmpty).onEach { event ->
            val content = event.orNull()?.content?.toModel<RoomServerAclContent>() ?: RoomServerAclContent()
            if (hasLoadedAcl) return@onEach
            hasLoadedAcl = true
            draftEntries = content.allowList.map { RoomAclEntry(nextId++, it, true) } + content.denyList.map { RoomAclEntry(nextId++, it, false) }
            draftAllowIpLiterals = content.allowIpLiterals
            initialDraft = roomAclDraft(draftEntries, draftAllowIpLiterals)
            setState {
                copy(
                        entries = draftEntries,
                        allowIpLiterals = draftAllowIpLiterals,
                        loading = false
                )
            }
        }.launchIn(viewModelScope)
        room.flow().liveRoomPowerLevels().onEach { levels ->
            setState { copy(canEdit = levels.isUserAllowedToSend(session.myUserId, true, EventType.STATE_ROOM_SERVER_ACL)) }
        }.launchIn(viewModelScope)
    }

    override fun handle(action: RoomAclAction) {
        when (action) {
            is RoomAclAction.Add -> {
                val position = draftEntries.indexOfFirst { it.allowed == action.allowed }
                        .let { if (it >= 0) it else draftEntries.size }
                draftEntries = draftEntries.toMutableList().apply { add(position, RoomAclEntry(nextId++, "", action.allowed)) }
                setState { copy(entries = draftEntries) }
            }
            is RoomAclAction.Update -> {
                val entry = draftEntries.firstOrNull { it.id == action.id }
                if (entry != null) {
                    val updated = entry.copy(server = action.server, allowed = action.allowed)
                    draftEntries = draftEntries.map { if (it.id == action.id) updated else it }
                    if (entry.allowed != action.allowed) {
                        setState { copy(entries = draftEntries) }
                    }
                }
            }
            is RoomAclAction.ToggleType -> {
                draftEntries = draftEntries.map { entry ->
                    if (entry.id == action.id) entry.copy(allowed = !entry.allowed) else entry
                }
                setState { copy(entries = draftEntries) }
            }
            is RoomAclAction.Remove -> {
                draftEntries = draftEntries.filterNot { it.id == action.id }
                setState { copy(entries = draftEntries) }
            }
            is RoomAclAction.ReplaceOrder -> {
                val entriesById = draftEntries.associateBy { it.id }
                draftEntries = action.entries.map { reordered ->
                    (entriesById[reordered.id] ?: reordered).copy(allowed = reordered.allowed)
                }
                setState { copy(entries = draftEntries) }
            }
            is RoomAclAction.SetIpLiterals -> {
                draftAllowIpLiterals = action.value
                setState { copy(allowIpLiterals = action.value) }
            }
            is RoomAclAction.ToggleExpanded -> setState {
                if (action.allowed) copy(allowedExpanded = !allowedExpanded) else copy(deniedExpanded = !deniedExpanded)
            }
            is RoomAclAction.Search -> setState { copy(entries = draftEntries, query = action.query) }
            RoomAclAction.Save -> save()
        }
        if (action != RoomAclAction.Save && action !is RoomAclAction.Search && action !is RoomAclAction.ToggleExpanded) {
            _viewEvents.post(RoomAclEvent.DirtyChanged)
        }
    }

    private fun save() = withState { state ->
        if (!state.canEdit) return@withState
        setState { copy(saving = true) }
        viewModelScope.launch {
            try {
                val valid = draftEntries.filter { it.server.isNotBlank() }
                room.stateService().sendStateEvent(EventType.STATE_ROOM_SERVER_ACL, "", RoomServerAclContent(
                        allowIpLiterals = draftAllowIpLiterals,
                        allowList = valid.filter { it.allowed }.map { it.server },
                        denyList = valid.filterNot { it.allowed }.map { it.server }
                ).toContent())
                initialDraft = roomAclDraft(draftEntries, draftAllowIpLiterals)
                setState { copy(saving = false) }
                _viewEvents.post(RoomAclEvent.Saved)
            } catch (failure: Throwable) {
                setState { copy(saving = false) }
                _viewEvents.post(RoomAclEvent.Failure(failure))
            }
        }
    }
}

private data class RoomAclDraft(val entries: List<RoomAclEntry>, val allowIpLiterals: Boolean)

private fun roomAclDraft(entries: List<RoomAclEntry>, allowIpLiterals: Boolean) =
        RoomAclDraft(entries.filter { it.server.isNotBlank() && it.allowed }.sortedBy { it.id } + entries.filter { it.server.isNotBlank() && !it.allowed }.sortedBy { it.id }, allowIpLiterals)

sealed class RoomAclAction : VectorViewModelAction {
    data class Add(val allowed: Boolean) : RoomAclAction()
    data class Update(val id: Long, val server: String, val allowed: Boolean) : RoomAclAction()
    data class ToggleType(val id: Long) : RoomAclAction()
    data class Remove(val id: Long) : RoomAclAction()
    data class ReplaceOrder(val entries: List<RoomAclEntry>) : RoomAclAction()
    data class SetIpLiterals(val value: Boolean) : RoomAclAction()
    data class ToggleExpanded(val allowed: Boolean) : RoomAclAction()
    data class Search(val query: String) : RoomAclAction()
    object Save : RoomAclAction()
}
sealed class RoomAclEvent : VectorViewEvents {
    object DirtyChanged : RoomAclEvent()
    object Saved : RoomAclEvent()
    data class Failure(val throwable: Throwable) : RoomAclEvent()
}
