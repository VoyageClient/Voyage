/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.roommemberprofile.mutualrooms

import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MavericksState
import com.airbnb.mvrx.Uninitialized
import org.matrix.android.sdk.api.util.MatrixItem

data class MutualRoomsViewState(
        val userId: String,
        val items: Async<List<MutualRoomsListItem>> = Uninitialized
) : MavericksState {

    constructor(args: MutualRoomsArgs) : this(userId = args.userId)
}

sealed interface MutualRoomsListItem {
    data class SpaceHeader(val item: MatrixItem) : MutualRoomsListItem
    data class Room(val item: MatrixItem, val indented: Boolean) : MutualRoomsListItem
}
