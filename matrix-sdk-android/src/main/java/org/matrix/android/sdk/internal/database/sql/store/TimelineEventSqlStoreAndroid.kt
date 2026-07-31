/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.sqldelight.asLiveList

// LiveData views of TimelineEventSqlStore, kept in the android layer; the core module observes the
// underlying queries as Flow instead.

internal fun TimelineEventSqlStore.getRootThreadsForRoomLive(roomId: String, dispatcher: CoroutineDispatcher): LiveData<List<TimelineEventEntity>> =
        queries.selectRootThreadsForRoom(roomId).asLiveList(dispatcher).map { rows -> with(this) { rows.toEntities() } }

internal fun TimelineEventSqlStore.getLocalThreadNotificationsForRoomLive(roomId: String, dispatcher: CoroutineDispatcher): LiveData<List<TimelineEventEntity>> =
        queries.selectLocalThreadNotificationsForRoom(roomId).asLiveList(dispatcher).map { rows -> with(this) { rows.toEntities() } }
