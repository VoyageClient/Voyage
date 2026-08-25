/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list.actions

import com.airbnb.mvrx.MavericksViewModelFactory
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.SpaceStateHandler
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.features.home.room.list.sections.RoomSections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.model.tag.RoomTag
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.flow.flow

class RoomSectionViewModel @AssistedInject constructor(
        @Assisted initialState: RoomSectionViewState,
        private val session: Session,
        private val spaceStateHandler: SpaceStateHandler,
) : VectorViewModel<RoomSectionViewState, RoomSectionAction, RoomSectionViewEvents>(initialState) {

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<RoomSectionViewModel, RoomSectionViewState> {
        override fun create(initialState: RoomSectionViewState): RoomSectionViewModel
    }

    companion object : MavericksViewModelFactory<RoomSectionViewModel, RoomSectionViewState> by hiltMavericksViewModelFactory()

    private val room = session.getRoom(initialState.roomId)

    init {
        observeSections()
    }

    private fun observeSections() {
        val room = room ?: return
        combine(
                room.flow().liveRoomSummary().distinctUntilChanged(),
                RoomSections.flow(session),
        ) { summaryOption, config ->
            val tagNames = summaryOption.getOrNull()?.tags?.map { it.name }.orEmpty()
            val currentTag = tagNames.firstOrNull { RoomSections.isSectionTag(it) }
            currentTag to config.all
        }
                .setOnEach { (currentTag, sections) ->
                    copy(currentSectionTag = currentTag, sections = sections)
                }
    }

    override fun handle(action: RoomSectionAction) {
        when (action) {
            is RoomSectionAction.MoveToSection -> moveToSection(action.tag)
            is RoomSectionAction.CreateSectionAndMove -> createSectionAndMove(action.name)
            is RoomSectionAction.RenameSection -> renameSection(action)
            is RoomSectionAction.RequestDeleteSection -> requestDeleteSection(action)
            is RoomSectionAction.DeleteSection -> deleteSection(action)
        }
    }

    private fun renameSection(action: RoomSectionAction.RenameSection) {
        viewModelScope.launch {
            runCatching { RoomSections.renameSection(session, action.tag, action.newName) }
                    .onFailure { _viewEvents.post(RoomSectionViewEvents.Failure(it)) }
        }
    }

    private fun requestDeleteSection(action: RoomSectionAction.RequestDeleteSection) {
        viewModelScope.launch {
            val isEmpty = withContext(Dispatchers.IO) {
                session.roomService().getRoomSummaries(roomSummaryQueryParams { hasTag = action.tag }).isEmpty()
            }
            _viewEvents.post(RoomSectionViewEvents.PromptDeleteSection(action.tag, isEmpty))
        }
    }

    private fun deleteSection(action: RoomSectionAction.DeleteSection) {
        viewModelScope.launch {
            runCatching { RoomSections.deleteSection(session, action.tag) }
                    .onSuccess {
                        // Deleting the section this room sits in makes the sheet moot.
                        if (awaitState().currentSectionTag == action.tag) {
                            _viewEvents.post(RoomSectionViewEvents.Dismiss)
                        }
                    }
                    .onFailure { _viewEvents.post(RoomSectionViewEvents.Failure(it)) }
        }
    }

    private fun moveToSection(target: String?) {
        val room = room ?: return
        viewModelScope.launch {
            try {
                val tags = room.roomSummary()?.tags?.map { it.name }.orEmpty()
                // Web treats favourite, low priority and custom sections as one exclusive slot, so
                // moving into a section atomically replaces whichever of those the room carries.
                // Plain removal only clears the custom section (back to the catch-all).
                val toRemove = tags.filter { tag ->
                    tag != target && (
                            RoomSections.isSectionTag(tag) ||
                                    (target != null && (tag == RoomTag.ROOM_TAG_FAVOURITE || tag == RoomTag.ROOM_TAG_LOW_PRIORITY))
                            )
                }
                toRemove.forEach { room.tagsService().deleteTag(it) }
                if (target != null && target !in tags) {
                    room.tagsService().addTag(target, 0.5)
                }
                _viewEvents.post(RoomSectionViewEvents.Dismiss)
            } catch (failure: Throwable) {
                _viewEvents.post(RoomSectionViewEvents.Failure(failure))
            }
        }
    }

    private fun createSectionAndMove(name: String) {
        viewModelScope.launch {
            try {
                val spaceId = spaceStateHandler.getCurrentSpace()?.roomId ?: RoomSections.HOME_SPACE_ID
                val tag = RoomSections.createSection(session, name, spaceId)
                moveToSection(tag)
            } catch (failure: Throwable) {
                _viewEvents.post(RoomSectionViewEvents.Failure(failure))
            }
        }
    }
}
