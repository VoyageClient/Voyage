/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.devtools

import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.Success
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Types
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.error.ErrorFormatter
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.resources.StringProvider
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.launch
import okio.Buffer
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.accountdata.RoomAccountDataEvent
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.api.util.MatrixJsonParser
import org.matrix.android.sdk.flow.flow

class RoomDevToolViewModel @AssistedInject constructor(
        @Assisted val initialState: RoomDevToolViewState,
        private val errorFormatter: ErrorFormatter,
        private val stringProvider: StringProvider,
        private val session: Session
) : VectorViewModel<RoomDevToolViewState, RoomDevToolAction, DevToolsViewEvents>(initialState) {

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<RoomDevToolViewModel, RoomDevToolViewState> {
        override fun create(initialState: RoomDevToolViewState): RoomDevToolViewModel
    }

    companion object : MavericksViewModelFactory<RoomDevToolViewModel, RoomDevToolViewState> by hiltMavericksViewModelFactory()

    private val contentAdapter: JsonAdapter<JsonDict> = MatrixJsonParser.getMoshi()
            .adapter(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))

    // Lenient so minor hand-editing of the JSON isn't rejected outright.
    private fun parseJsonLeniently(text: String): JsonDict? {
        return contentAdapter.fromJson(JsonReader.of(Buffer().writeUtf8(text)).apply { isLenient = true })
                ?.let { coerceContent(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun coerceContent(content: JsonDict?): JsonDict? = coerceWholeDoublesToLongs(content) as? JsonDict

    // Moshi's Any adapter parses every JSON number as Double, so re-serializing would emit "w":1080.0 —
    // Synapse strictly rejects that (M_BAD_JSON "Bad JSON value: float"). Round-trip whole-number Doubles
    // back to Long. Same fix as MessageActionsViewModel / LocalEchoEventFactory.
    private fun coerceWholeDoublesToLongs(value: Any?): Any? = when (value) {
        is Double -> if (value.isFinite() && value % 1.0 == 0.0 &&
                value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()) {
            value.toLong()
        } else value
        is Map<*, *> -> value.mapValues { coerceWholeDoublesToLongs(it.value) }
        is List<*> -> value.map { coerceWholeDoublesToLongs(it) }
        else -> value
    }

    init {
        setState {
            copy(canEditState = session.getRoom(initialState.roomId)?.roomSummary()?.membership == Membership.JOIN)
        }
        session.getRoom(initialState.roomId)
                ?.flow()
                ?.liveStateEvents(emptySet(), QueryStringValue.IsNotNull)
                ?.execute { async ->
                    copy(stateEvents = async)
                }
        session.getRoom(initialState.roomId)
                ?.flow()
                ?.liveRoomAccountData(emptySet())
                ?.execute { async ->
                    copy(roomAccountData = async)
                }
    }

    override fun handle(action: RoomDevToolAction) {
        when (action) {
            RoomDevToolAction.ExploreRoomState -> {
                setState {
                    copy(
                            displayMode = RoomDevToolViewState.Mode.StateEventList,
                            selectedEvent = null
                    )
                }
            }
            RoomDevToolAction.ExploreRoomAccountData -> {
                setState {
                    copy(
                            displayMode = RoomDevToolViewState.Mode.AccountDataList,
                            selectedAccountData = null
                    )
                }
            }
            is RoomDevToolAction.ShowStateEvent -> {
                showStateEventDetail(action.event)
            }
            is RoomDevToolAction.ShowAccountDataEvent -> {
                val sanitized = action.event.copy(content = coerceContent(action.event.content).orEmpty())
                val jsonString = MatrixJsonParser.getMoshi()
                        .adapter(RoomAccountDataEvent::class.java)
                        .toJson(sanitized)
                setState {
                    copy(
                            displayMode = RoomDevToolViewState.Mode.AccountDataDetail,
                            selectedAccountData = sanitized,
                            selectedEvent = null,
                            selectedEventJson = jsonString
                    )
                }
            }
            RoomDevToolAction.OnBackPressed -> {
                handleBack()
            }
            RoomDevToolAction.MenuEdit -> {
                withState {
                    // Serialize with Moshi (the same parser used on save) so the content round-trips
                    // exactly — org.json mangles nested maps and escapes '/', which broke saving.
                    val contentMap = when (it.displayMode) {
                        RoomDevToolViewState.Mode.StateEventDetail -> it.selectedEvent?.content
                        RoomDevToolViewState.Mode.AccountDataDetail -> it.selectedAccountData?.content
                        else -> return@withState
                    }
                    val content = contentMap?.let { map -> contentAdapter.indent("    ").toJson(map) } ?: "{\n\t\n}"
                    setState {
                        copy(
                                editedContent = content,
                                displayMode = RoomDevToolViewState.Mode.EditEventContent
                        )
                    }
                }
            }
            is RoomDevToolAction.ShowStateEventType -> withState { state ->
                // A type with a single empty-state-key event has no per-key list worth showing — open its
                // detail directly. Multiple keys (or a non-empty key) still get the intermediate list.
                val single = singleEmptyKeyEvent(state, action.stateEventType)
                if (single != null) {
                    setState { copy(currentStateType = action.stateEventType) }
                    showStateEventDetail(single)
                } else {
                    setState {
                        copy(
                                displayMode = RoomDevToolViewState.Mode.StateEventListByType,
                                currentStateType = action.stateEventType
                        )
                    }
                }
            }
            RoomDevToolAction.MenuItemSend -> {
                handleMenuItemSend()
            }
            is RoomDevToolAction.UpdateContentText -> {
                setState {
                    copy(editedContent = action.contentJson)
                }
            }
            is RoomDevToolAction.SendCustomEvent -> {
                setState {
                    copy(
                            displayMode = RoomDevToolViewState.Mode.SendEventForm(action.isStateEvent),
                            sendEventDraft = RoomDevToolViewState.SendEventDraft(EventType.MESSAGE, null, "{\n}")
                    )
                }
            }
            is RoomDevToolAction.CustomEventTypeChange -> {
                setState {
                    copy(
                            sendEventDraft = sendEventDraft?.copy(type = action.type)
                    )
                }
            }
            is RoomDevToolAction.CustomEventStateKeyChange -> {
                setState {
                    copy(
                            sendEventDraft = sendEventDraft?.copy(stateKey = action.stateKey)
                    )
                }
            }
            is RoomDevToolAction.CustomEventContentChange -> {
                setState {
                    copy(
                            sendEventDraft = sendEventDraft?.copy(content = action.content)
                    )
                }
            }
        }
    }

    private fun handleMenuItemSend() = withState { state ->
        when (state.displayMode) {
            RoomDevToolViewState.Mode.EditEventContent -> {
                if (state.selectedAccountData != null) {
                    editAccountDataContent(state, state.selectedAccountData)
                } else {
                    editEventContent(state)
                }
            }
            is RoomDevToolViewState.Mode.SendEventForm -> sendEventContent(state, state.displayMode.isState)
            else -> Unit
        }
    }

    private fun editAccountDataContent(state: RoomDevToolViewState, accountData: RoomAccountDataEvent) {
        setState { copy(modalLoading = Loading()) }

        viewModelScope.launch {
            try {
                val room = session.getRoom(initialState.roomId)
                        ?: throw IllegalArgumentException(stringProvider.getString(CommonStrings.room_error_not_found))

                val json = parseJsonLeniently(state.editedContent ?: "")
                        ?: throw IllegalArgumentException(stringProvider.getString(CommonStrings.dev_tools_error_no_content))

                room.roomAccountDataService().updateAccountData(accountData.type, json)
                _viewEvents.post(DevToolsViewEvents.ShowSnackMessage(stringProvider.getString(CommonStrings.dev_tools_success_account_data)))
                setState {
                    copy(
                            modalLoading = Success(Unit),
                            selectedAccountData = null,
                            selectedEventJson = null,
                            editedContent = null,
                            displayMode = RoomDevToolViewState.Mode.AccountDataList
                    )
                }
            } catch (failure: Throwable) {
                _viewEvents.post(DevToolsViewEvents.ShowAlertMessage(errorFormatter.toHumanReadable(failure)))
                setState { copy(modalLoading = Fail(failure)) }
            }
        }
    }

    private fun editEventContent(state: RoomDevToolViewState) {
        setState { copy(modalLoading = Loading()) }

        viewModelScope.launch {
            try {
                val room = session.getRoom(initialState.roomId)
                        ?: throw IllegalArgumentException(stringProvider.getString(CommonStrings.room_error_not_found))

                val json = parseJsonLeniently(state.editedContent ?: "")
                        ?: throw IllegalArgumentException(stringProvider.getString(CommonStrings.dev_tools_error_no_content))

                room.stateService().sendStateEvent(
                        state.selectedEvent?.type.orEmpty(),
                        state.selectedEvent?.stateKey.orEmpty(),
                        json
                )
                _viewEvents.post(DevToolsViewEvents.ShowSnackMessage(stringProvider.getString(CommonStrings.dev_tools_success_state_event)))
                setState {
                    copy(
                            modalLoading = Success(Unit),
                            selectedEventJson = null,
                            editedContent = null,
                            displayMode = RoomDevToolViewState.Mode.StateEventListByType
                    )
                }
            } catch (failure: Throwable) {
                timber.log.Timber.e(failure, "DevToolsDbg: editEventContent failed; content=[${state.editedContent}]")
                _viewEvents.post(DevToolsViewEvents.ShowAlertMessage(errorFormatter.toHumanReadable(failure)))
                setState { copy(modalLoading = Fail(failure)) }
            }
        }
    }

    private fun sendEventContent(state: RoomDevToolViewState, isState: Boolean) {
        setState { copy(modalLoading = Loading()) }
        viewModelScope.launch {
            try {
                val room = session.getRoom(initialState.roomId)
                        ?: throw IllegalArgumentException(stringProvider.getString(CommonStrings.room_error_not_found))

                val json = parseJsonLeniently(state.sendEventDraft?.content ?: "")
                        ?: throw IllegalArgumentException(stringProvider.getString(CommonStrings.dev_tools_error_no_content))

                val eventType = state.sendEventDraft?.type
                        ?: throw IllegalArgumentException(stringProvider.getString(CommonStrings.dev_tools_error_no_message_type))

                if (isState) {
                    room.stateService().sendStateEvent(
                            eventType,
                            state.sendEventDraft.stateKey.orEmpty(),
                            json
                    )
                } else {
                    room.sendService().sendEvent(
                            eventType,
                            json
                    )
                }

                _viewEvents.post(DevToolsViewEvents.ShowSnackMessage(stringProvider.getString(CommonStrings.dev_tools_success_event)))
                setState {
                    copy(
                            modalLoading = Success(Unit),
                            sendEventDraft = null,
                            displayMode = RoomDevToolViewState.Mode.Root
                    )
                }
            } catch (failure: Throwable) {
                _viewEvents.post(DevToolsViewEvents.ShowAlertMessage(errorFormatter.toHumanReadable(failure)))
                setState { copy(modalLoading = Fail(failure)) }
            }
        }
    }

    private fun showStateEventDetail(event: Event) {
        // Coerce up front so the source we show (and copy to clipboard) is already correct JSON —
        // integers, not "size":15394.0 floats from Moshi's Any adapter.
        val sanitizedEvent = event.copy(
                content = coerceContent(event.content),
                prevContent = coerceContent(event.prevContent),
        )
        val jsonString = MatrixJsonParser.getMoshi()
                .adapter(Event::class.java)
                .toJson(sanitizedEvent)
        setState {
            copy(
                    displayMode = RoomDevToolViewState.Mode.StateEventDetail,
                    selectedEvent = sanitizedEvent,
                    selectedAccountData = null,
                    selectedEventJson = jsonString
            )
        }
    }

    private fun singleEmptyKeyEvent(state: RoomDevToolViewState, type: String?): Event? {
        return state.stateEvents.invoke().orEmpty()
                .filter { it.type == type }
                .singleOrNull()
                ?.takeIf { it.stateKey == "" }
    }

    private fun handleBack() = withState {
        when (it.displayMode) {
            RoomDevToolViewState.Mode.Root -> {
                _viewEvents.post(DevToolsViewEvents.Dismiss)
            }
            RoomDevToolViewState.Mode.StateEventList -> {
                setState {
                    copy(
                            selectedEvent = null,
                            selectedEventJson = null,
                            displayMode = RoomDevToolViewState.Mode.Root
                    )
                }
            }
            RoomDevToolViewState.Mode.AccountDataList -> {
                setState {
                    copy(
                            selectedAccountData = null,
                            selectedEventJson = null,
                            displayMode = RoomDevToolViewState.Mode.Root
                    )
                }
            }
            RoomDevToolViewState.Mode.AccountDataDetail -> {
                setState {
                    copy(
                            selectedAccountData = null,
                            selectedEventJson = null,
                            displayMode = RoomDevToolViewState.Mode.AccountDataList
                    )
                }
            }
            RoomDevToolViewState.Mode.StateEventDetail -> {
                // Mirror the forward skip: if we jumped straight here (single empty-key event), skip the
                // intermediate list on the way back too.
                val skipList = singleEmptyKeyEvent(it, it.currentStateType) != null
                setState {
                    copy(
                            selectedEvent = null,
                            selectedEventJson = null,
                            currentStateType = if (skipList) null else currentStateType,
                            displayMode = if (skipList) RoomDevToolViewState.Mode.StateEventList else RoomDevToolViewState.Mode.StateEventListByType
                    )
                }
            }
            RoomDevToolViewState.Mode.EditEventContent -> {
                setState {
                    copy(
                            displayMode = if (selectedAccountData != null) {
                                RoomDevToolViewState.Mode.AccountDataDetail
                            } else {
                                RoomDevToolViewState.Mode.StateEventDetail
                            }
                    )
                }
            }
            RoomDevToolViewState.Mode.StateEventListByType -> {
                setState {
                    copy(
                            currentStateType = null,
                            displayMode = RoomDevToolViewState.Mode.StateEventList
                    )
                }
            }
            is RoomDevToolViewState.Mode.SendEventForm -> {
                setState {
                    copy(
                            displayMode = RoomDevToolViewState.Mode.Root
                    )
                }
            }
        }
    }
}
