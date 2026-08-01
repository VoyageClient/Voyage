/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity

// Flow views of TimelineEventSqlStore's thread queries.

internal fun TimelineEventSqlStore.getRootThreadsForRoomFlow(roomId: String, dispatcher: CoroutineDispatcher): Flow<List<TimelineEventEntity>> =
        queries.selectRootThreadsForRoom(roomId).asFlow().mapToList(dispatcher).map { rows -> with(this) { rows.toEntities() } }

internal fun TimelineEventSqlStore.getLocalThreadNotificationsForRoomFlow(roomId: String, dispatcher: CoroutineDispatcher): Flow<List<TimelineEventEntity>> =
        queries.selectLocalThreadNotificationsForRoom(roomId).asFlow().mapToList(dispatcher).map { rows -> with(this) { rows.toEntities() } }
