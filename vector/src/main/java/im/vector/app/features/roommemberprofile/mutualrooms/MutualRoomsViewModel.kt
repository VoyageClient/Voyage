/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roommemberprofile.mutualrooms

import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MavericksViewModelFactory
import com.airbnb.mvrx.Success
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.platform.EmptyAction
import im.vector.app.core.platform.EmptyViewEvents
import im.vector.app.core.platform.VectorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.api.session.room.spaceSummaryQueryParams
import org.matrix.android.sdk.api.util.toMatrixItem

class MutualRoomsViewModel @AssistedInject constructor(
        @Assisted private val initialState: MutualRoomsViewState,
        private val session: Session
) : VectorViewModel<MutualRoomsViewState, EmptyAction, EmptyViewEvents>(initialState) {

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<MutualRoomsViewModel, MutualRoomsViewState> {
        override fun create(initialState: MutualRoomsViewState): MutualRoomsViewModel
    }

    companion object : MavericksViewModelFactory<MutualRoomsViewModel, MutualRoomsViewState> by hiltMavericksViewModelFactory()

    override fun handle(action: EmptyAction) = Unit

    init {
        setState { copy(items = Loading()) }
        viewModelScope.launch {
            val items = withContext(Dispatchers.Default) { computeItems() }
            setState { copy(items = Success(items)) }
        }
    }

    private fun computeItems(): List<MutualRoomsListItem> {
        val mutualRoomIds = session.roomService().getRoomIdsWithUserActiveMembership(initialState.userId).toSet()
        val mutualRooms = session.roomService()
                .getRoomSummaries(roomSummaryQueryParams { memberships = listOf(Membership.JOIN) })
                .filter { it.roomId in mutualRoomIds }

        // Top-level spaces (matching the sidebar) we can bucket rooms under.
        val rootSpaces = session.spaceService()
                .getSpaceSummaries(spaceSummaryQueryParams { memberships = listOf(Membership.JOIN) })
                .filter { it.flattenParentIds.isEmpty() }

        val items = mutableListOf<MutualRoomsListItem>()
        val grouped = mutableSetOf<String>()
        rootSpaces.forEach { space ->
            val roomsInSpace = mutualRooms.filter { space.roomId in it.flattenParentIds }
            if (roomsInSpace.isNotEmpty()) {
                items += MutualRoomsListItem.SpaceHeader(space.toMatrixItem())
                roomsInSpace.forEach { room ->
                    items += MutualRoomsListItem.Room(room.toMatrixItem(), indented = true)
                    grouped += room.roomId
                }
            }
        }
        mutualRooms.filterNot { it.roomId in grouped }.forEach { room ->
            items += MutualRoomsListItem.Room(room.toMatrixItem(), indented = false)
        }
        return items
    }
}
