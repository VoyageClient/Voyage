/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.share

import com.airbnb.mvrx.MavericksViewModelFactory
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.extensions.toggle
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.features.attachments.SendMediaMaterializer
import im.vector.app.features.attachments.toGroupedContentAttachmentData
import im.vector.app.features.home.room.list.BreadcrumbsRoomComparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.getRoomSummary
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.flow.flow

class IncomingShareViewModel @AssistedInject constructor(
        @Assisted initialState: IncomingShareViewState,
        private val session: Session,
        private val breadcrumbsRoomComparator: BreadcrumbsRoomComparator,
        private val sendMediaMaterializer: SendMediaMaterializer
) :
        VectorViewModel<IncomingShareViewState, IncomingShareAction, IncomingShareViewEvents>(initialState) {

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<IncomingShareViewModel, IncomingShareViewState> {
        override fun create(initialState: IncomingShareViewState): IncomingShareViewModel
    }

    companion object : MavericksViewModelFactory<IncomingShareViewModel, IncomingShareViewState> by hiltMavericksViewModelFactory()

    private val filterStream = MutableStateFlow("")

    init {
        observeRoomSummaries()
    }

    private fun observeRoomSummaries() {
        val queryParams = roomSummaryQueryParams {
            memberships = listOf(Membership.JOIN)
        }
        session
                .flow().liveRoomSummaries(queryParams)
                .execute {
                    copy(roomSummaries = it)
                }

        filterStream
                .flatMapLatest { filter ->
                    val displayNameQuery = if (filter.isEmpty()) {
                        QueryStringValue.NoCondition
                    } else {
                        QueryStringValue.Contains(filter, QueryStringValue.Case.INSENSITIVE)
                    }
                    val filterQueryParams = roomSummaryQueryParams {
                        displayName = displayNameQuery
                        memberships = listOf(Membership.JOIN)
                    }
                    session.flow().liveRoomSummaries(filterQueryParams)
                }
                .sample(300)
                .map { it.sortedWith(breadcrumbsRoomComparator) }
                .execute {
                    copy(filteredRoomSummaries = it)
                }
    }

    override fun handle(action: IncomingShareAction) {
        when (action) {
            is IncomingShareAction.SelectRoom -> handleSelectRoom(action)
            is IncomingShareAction.ShareToSelectedRooms -> handleShareToSelectedRooms()
            is IncomingShareAction.ShareToRoom -> handleShareToRoom(action)
            is IncomingShareAction.ShareMedia -> handleShareMediaToSelectedRooms(action)
            is IncomingShareAction.FilterWith -> handleFilter(action)
            is IncomingShareAction.UpdateSharedData -> handleUpdateSharedData(action)
        }
    }

    private fun handleUpdateSharedData(action: IncomingShareAction.UpdateSharedData) {
        setState { copy(sharedData = action.sharedData) }
    }

    private fun handleFilter(action: IncomingShareAction.FilterWith) {
        filterStream.tryEmit(action.filter)
    }

    private fun handleShareToSelectedRooms() = withState { state ->
        val sharedData = state.sharedData ?: return@withState
        if (state.selectedRoomIds.isEmpty()) return@withState
        // A forward is sent straight away, so it never goes through the room screen.
        if (state.selectedRoomIds.size == 1 && sharedData !is SharedData.Forward) {
            // In this case the edition of the media will be handled by the RoomDetailFragment
            val selectedRoomId = state.selectedRoomIds.first()
            val selectedRoom = state.roomSummaries()?.find { it.roomId == selectedRoomId } ?: return@withState
            _viewEvents.post(IncomingShareViewEvents.ShareToRoom(selectedRoom, sharedData))
        } else {
            when (sharedData) {
                is SharedData.Text -> {
                    state.selectedRoomIds.forEach { roomId ->
                        val room = session.getRoom(roomId)
                        room?.sendService()?.sendTextMessage(sharedData.text)
                    }
                    _viewEvents.post(IncomingShareViewEvents.MultipleRoomsShareDone(state.selectedRoomIds.singleOrNull()))
                }
                is SharedData.Attachments -> {
                    shareAttachments(
                            attachmentData = sharedData.attachmentData,
                            captions = sharedData.captions,
                            selectedRoomIds = state.selectedRoomIds,
                            proposeMediaEdition = true,
                            compressMediaBeforeSending = false,
                    )
                }
                is SharedData.Forward -> {
                    forwardToRooms(sharedData, state.selectedRoomIds)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun forwardToRooms(forward: SharedData.Forward, roomIds: Set<String>) {
        val content = ForwardPayloadHolder.take(forward.payloadId) as? Map<String, Any> ?: run {
            _viewEvents.post(IncomingShareViewEvents.ForwardFailed)
            return
        }
        roomIds.forEach { roomId ->
            session.getRoom(roomId)?.sendService()?.sendEvent(forward.eventType, content)
        }
        // Opening a room only makes sense when there is exactly one to open.
        _viewEvents.post(IncomingShareViewEvents.ForwardDone(roomIds.singleOrNull()))
    }

    private fun handleShareToRoom(action: IncomingShareAction.ShareToRoom) = withState { state ->
        val sharedData = state.sharedData ?: return@withState
        val roomSummary = session.getRoomSummary(action.roomId) ?: return@withState
        _viewEvents.post(IncomingShareViewEvents.ShareToRoom(roomSummary, sharedData))
    }

    private fun handleShareMediaToSelectedRooms(action: IncomingShareAction.ShareMedia) = withState { state ->
        (state.sharedData as? SharedData.Attachments)?.let {
            shareAttachments(
                    attachmentData = it.attachmentData,
                    captions = it.captions,
                    selectedRoomIds = state.selectedRoomIds,
                    proposeMediaEdition = false,
                    compressMediaBeforeSending = !action.keepOriginalSize,
            )
        }
    }

    private fun shareAttachments(
            attachmentData: List<ContentAttachmentData>,
            captions: List<String> = emptyList(),
            selectedRoomIds: Set<String>,
            proposeMediaEdition: Boolean,
            compressMediaBeforeSending: Boolean
    ) {
        if (proposeMediaEdition) {
            val grouped = attachmentData.toGroupedContentAttachmentData()
            if (grouped.notPreviewables.isNotEmpty()) {
                // Send the not previewable attachments right now (?)
                // Pick the first room to send the media
                selectedRoomIds.firstOrNull()
                        ?.let { roomId -> session.getRoom(roomId) }
                        ?.sendService()
                        ?.let { sendService ->
                            viewModelScope.launch(Dispatchers.IO) {
                                sendService.sendMedias(sendMediaMaterializer.materialize(grouped.notPreviewables), compressMediaBeforeSending, selectedRoomIds)
                            }
                        }

                // Ensure they will not be sent twice
                setState {
                    copy(
                            sharedData = SharedData.Attachments(grouped.previewables)
                    )
                }
            }
            if (grouped.previewables.isNotEmpty()) {
                // In case of multiple share of media, edit them first
                _viewEvents.post(IncomingShareViewEvents.EditMediaBeforeSending(grouped.previewables))
            } else {
                _viewEvents.post(IncomingShareViewEvents.MultipleRoomsShareDone(selectedRoomIds.singleOrNull()))
            }
        } else {
            // Pick the first room to send the media
            selectedRoomIds.firstOrNull()
                    ?.let { roomId -> session.getRoom(roomId) }
                    ?.sendService()
                    ?.let { sendService ->
                        viewModelScope.launch(Dispatchers.IO) {
                            val materialized = sendMediaMaterializer.materialize(attachmentData)
                            if (captions.any { it.isNotBlank() }) {
                                // Each was captioned for itself in the previewer, so each goes out on its own.
                                materialized.forEachIndexed { index, attachment ->
                                    sendService.sendMedia(
                                            attachment = attachment,
                                            compressBeforeSending = compressMediaBeforeSending,
                                            roomIds = selectedRoomIds,
                                            captionText = captions.getOrNull(index)?.takeIf { it.isNotBlank() },
                                    )
                                }
                            } else {
                                sendService.sendMedias(materialized, compressMediaBeforeSending, selectedRoomIds)
                            }
                        }
                    }
            _viewEvents.post(IncomingShareViewEvents.MultipleRoomsShareDone(selectedRoomIds.singleOrNull()))
        }
    }

    private fun handleSelectRoom(action: IncomingShareAction.SelectRoom) = setState {
        copy(selectedRoomIds = selectedRoomIds.toggle(action.roomSummary.roomId))
    }
}
