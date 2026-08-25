/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list.home.header

import org.matrix.android.sdk.api.session.room.model.RoomSummary

data class RoomsHeadersData(
        val invitesCount: Int = 0,
        val filtersList: List<HomeRoomFilterTab>? = null,
        val currentFilter: HomeRoomFilterTab = HomeRoomFilterTab.Standard(HomeRoomFilter.ALL),
        val canCreateSection: Boolean = true,
        val recents: List<RoomSummary>? = null
)
