/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.accountdata

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import org.matrix.android.sdk.api.session.room.accountdata.RoomAccountDataEvent
import org.matrix.android.sdk.api.util.Optional

// LiveData views over the data source's Flows, for the android-only internal consumers.
internal fun RoomAccountDataDataSource.getLiveAccountDataEvent(roomId: String, type: String): LiveData<Optional<RoomAccountDataEvent>> =
        getAccountDataEventFlow(roomId, type).asLiveData()

internal fun RoomAccountDataDataSource.getLiveAccountDataEvents(roomId: String?, types: Set<String>): LiveData<List<RoomAccountDataEvent>> =
        getAccountDataEventsFlow(roomId, types).asLiveData()
