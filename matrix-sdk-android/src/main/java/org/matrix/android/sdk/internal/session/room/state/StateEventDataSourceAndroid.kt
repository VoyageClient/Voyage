/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.state

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import org.matrix.android.sdk.api.query.QueryStateEventValue
import org.matrix.android.sdk.api.session.events.model.Event

internal fun StateEventDataSource.getStateEventsLive(
        roomId: String,
        eventTypes: Set<String>,
        stateKey: QueryStateEventValue
): LiveData<List<Event>> = getStateEventsFlow(roomId, eventTypes, stateKey).asLiveData()
