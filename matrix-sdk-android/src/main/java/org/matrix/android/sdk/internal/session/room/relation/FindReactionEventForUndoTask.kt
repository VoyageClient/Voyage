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

package org.matrix.android.sdk.internal.session.room.relation

import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal interface FindReactionEventForUndoTask : Task<FindReactionEventForUndoTask.Params, FindReactionEventForUndoTask.Result> {

    data class Params(
            val roomId: String,
            val eventId: String,
            val reaction: String
    )

    data class Result(
            val redactEventId: String?
    )
}

internal class DefaultFindReactionEventForUndoTask @Inject constructor(
        private val stores: SessionStores,
        @UserId private val userId: String,
) : FindReactionEventForUndoTask {

    override suspend fun execute(params: FindReactionEventForUndoTask.Params): FindReactionEventForUndoTask.Result {
        val summary = stores.annotations.get(params.eventId)
        val reaction = summary?.reactionsSummary?.firstOrNull { it.key == params.reaction }
        val eventId = reaction?.sourceEvents?.asSequence()
                ?.mapNotNull { stores.event.getByEventId(it) }
                ?.firstOrNull { it.sender == userId }
                ?.eventId
        return FindReactionEventForUndoTask.Result(eventId)
    }
}
