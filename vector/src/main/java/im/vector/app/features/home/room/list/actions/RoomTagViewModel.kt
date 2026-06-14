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
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.spaces.RoomTagItem
import im.vector.app.features.spaces.tags.displayNameForTag
import im.vector.app.features.spaces.tags.tagSortKey
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.flow.flow

class RoomTagViewModel @AssistedInject constructor(
        @Assisted initialState: RoomTagViewState,
        private val session: Session,
        private val stringProvider: StringProvider,
) : VectorViewModel<RoomTagViewState, RoomTagAction, RoomTagViewEvents>(initialState) {

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<RoomTagViewModel, RoomTagViewState> {
        override fun create(initialState: RoomTagViewState): RoomTagViewModel
    }

    companion object : MavericksViewModelFactory<RoomTagViewModel, RoomTagViewState> by hiltMavericksViewModelFactory()

    private val room = session.getRoom(initialState.roomId)

    init {
        observeTags()
    }

    private fun observeTags() {
        val room = room ?: return
        val allRoomsFlow = session.flow().liveRoomSummaries(
                roomSummaryQueryParams { memberships = listOf(Membership.JOIN) }
        )
        combine(
                room.flow().liveRoomSummary().distinctUntilChanged(),
                allRoomsFlow,
        ) { roomSummaryOption, allRooms ->
            val currentTagNames = roomSummaryOption.getOrNull()?.tags?.map { it.name }.orEmpty()
            val allTagNames = allRooms.flatMap { summary -> summary.tags.map { it.name } }.distinct()

            val roomTags = currentTagNames
                    .map { RoomTagItem(it, displayNameForTag(stringProvider, it), 0) }
                    .sortedBy { tagSortKey(it.name) }
            val availableTags = allTagNames
                    .filterNot { it in currentTagNames }
                    .map { RoomTagItem(it, displayNameForTag(stringProvider, it), 0) }
                    .sortedBy { tagSortKey(it.name) }
            roomTags to availableTags
        }
                .setOnEach { (roomTags, availableTags) ->
                    copy(roomTags = roomTags, availableTags = availableTags)
                }
    }

    override fun handle(action: RoomTagAction) {
        when (action) {
            is RoomTagAction.AddTag -> addTag(action.tag)
            is RoomTagAction.RemoveTag -> removeTag(action.tag)
        }
    }

    private fun addTag(tag: String) {
        val room = room ?: return
        viewModelScope.launch {
            try {
                room.tagsService().addTag(tag, 0.5)
            } catch (failure: Throwable) {
                _viewEvents.post(RoomTagViewEvents.Failure(failure))
            }
        }
    }

    private fun removeTag(tag: String) {
        val room = room ?: return
        viewModelScope.launch {
            try {
                room.tagsService().deleteTag(tag)
            } catch (failure: Throwable) {
                _viewEvents.post(RoomTagViewEvents.Failure(failure))
            }
        }
    }
}
