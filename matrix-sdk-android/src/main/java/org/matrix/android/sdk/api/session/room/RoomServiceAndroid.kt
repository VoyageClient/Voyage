/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.util.Optional

/**
 * Android-only LiveData views over the room-summary Flows. The services expose platform-neutral Flows
 * (so they can live in the shared core); consumers that still want LiveData use these. Not part of the
 * core module.
 */
fun Room.getRoomSummaryLive(): LiveData<Optional<RoomSummary>> = getRoomSummaryFlow().asLiveData()

fun RoomService.getRoomSummaryLive(roomId: String): LiveData<Optional<RoomSummary>> = getRoomSummaryFlow(roomId).asLiveData()
