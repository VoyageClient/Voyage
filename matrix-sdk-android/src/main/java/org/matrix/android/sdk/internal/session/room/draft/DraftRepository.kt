/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.room.draft

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.matrix.android.sdk.api.session.room.send.UserDraft
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.database.mapper.DraftMapper
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.room.summary.RoomSummaryPreviewInvalidation
import timber.log.Timber
import javax.inject.Inject

internal class DraftRepository @Inject constructor(
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val previewInvalidation: RoomSummaryPreviewInvalidation,
) {
    suspend fun saveDraft(roomId: String, userDraft: UserDraft) {
        database.awaitDbTransaction(dispatcher) {
            if (userDraft.isValid()) {
                Timber.d("Draft: create a new draft")
                stores.draft.replaceDrafts(roomId, listOf(DraftMapper.map(userDraft)))
                invalidateRoomSummary(roomId)
            } else {
                // Composer cleared: pop the top draft if any.
                val current = stores.draft.getDrafts(roomId)
                if (current.isEmpty()) {
                    Timber.d("Draft: nothing to do")
                } else {
                    Timber.d("Draft: remove the top draft")
                    stores.draft.replaceDrafts(roomId, current.dropLast(1))
                    invalidateRoomSummary(roomId)
                }
            }
        }
    }

    /** Replaces the room's drafts wholesale; the active one is last, as [getDraft] reads it. */
    suspend fun saveDrafts(roomId: String, drafts: List<UserDraft>) {
        database.awaitDbTransaction(dispatcher) {
            val valid = drafts.filter { it.isValid() }
            if (valid.isEmpty()) {
                stores.draft.deleteDrafts(roomId)
            } else {
                stores.draft.replaceDrafts(roomId, valid.map { DraftMapper.map(it) })
            }
            invalidateRoomSummary(roomId)
        }
    }

    suspend fun deleteDraft(roomId: String) {
        database.awaitDbTransaction(dispatcher) {
            stores.draft.deleteDrafts(roomId)
            invalidateRoomSummary(roomId)
        }
    }

    // Drafts are a separate table; nudge room_summary so the room list drops the stale draft badge.
    private fun invalidateRoomSummary(roomId: String) {
        previewInvalidation.onPreviewChanged(roomId)
        stores.roomSummary.touch(roomId)
    }

    // The last one is the active draft: an edit is stored on top of the message it interrupted.
    fun getDraft(roomId: String): UserDraft? = getDrafts(roomId).lastOrNull()

    fun getDrafts(roomId: String): List<UserDraft> =
            stores.draft.getDrafts(roomId).map { DraftMapper.map(it) }

    fun getDraftsFlow(roomId: String): Flow<Optional<UserDraft>> =
            database.draftQueries.selectByRoom(roomId)
                    .asFlow()
                    .mapToList(dispatcher)
                    .map { getDraft(roomId).toOptional() }
}
