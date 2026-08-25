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

package org.matrix.android.sdk.internal.session.room.tags

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import org.matrix.android.sdk.api.session.room.tags.TagsService
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase

internal class DefaultTagsService @AssistedInject constructor(
        @Assisted private val roomId: String,
        private val addTagToRoomTask: AddTagToRoomTask,
        private val deleteTagFromRoomTask: DeleteTagFromRoomTask,
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
) : TagsService {

    @AssistedFactory
    interface Factory {
        fun create(roomId: String): DefaultTagsService
    }

    override suspend fun addTag(tag: String, order: Double?) {
        val previous = applyLocally { tags -> tags.filter { it.first != tag } + (tag to order) }
        try {
            addTagToRoomTask.execute(AddTagToRoomTask.Params(roomId, tag, order))
        } catch (failure: Throwable) {
            restoreLocally(previous)
            throw failure
        }
    }

    override suspend fun deleteTag(tag: String) {
        val previous = applyLocally { tags -> tags.filter { it.first != tag } }
        try {
            deleteTagFromRoomTask.execute(DeleteTagFromRoomTask.Params(roomId, tag))
        } catch (failure: Throwable) {
            restoreLocally(previous)
            throw failure
        }
    }

    // Local echo before the upload so the room moves list sections instantly; the sync echo then
    // confirms it, and a rejected request rolls back to the previous tags.
    private suspend fun applyLocally(transform: (List<Pair<String, Double?>>) -> List<Pair<String, Double?>>): List<Pair<String, Double?>> {
        return database.awaitDbTransaction(dispatcher) {
            val current = database.roomTagQueries.selectByRoom(roomId).executeAsList().map { it.tag_name to it.tag_order }
            stores.roomSummary.updateTags(roomId, transform(current))
            current
        }
    }

    private suspend fun restoreLocally(tags: List<Pair<String, Double?>>) {
        database.awaitDbTransaction(dispatcher) {
            stores.roomSummary.updateTags(roomId, tags)
        }
    }
}
