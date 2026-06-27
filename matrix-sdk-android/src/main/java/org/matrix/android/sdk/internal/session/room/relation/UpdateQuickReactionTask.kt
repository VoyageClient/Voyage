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

internal interface UpdateQuickReactionTask : Task<UpdateQuickReactionTask.Params, UpdateQuickReactionTask.Result> {
    data class Params(
            val roomId: String,
            val eventId: String,
            val reaction: String,
            val oppositeReaction: String
    )

    data class Result(
            val reactionToAdd: String?,
            val reactionToRedact: List<String>
    )
}

internal class DefaultUpdateQuickReactionTask @Inject constructor(
        private val stores: SessionStores,
        @UserId private val userId: String,
) : UpdateQuickReactionTask {

    override suspend fun execute(params: UpdateQuickReactionTask.Params): UpdateQuickReactionTask.Result {
        val res = updateQuickReaction(params)
        return UpdateQuickReactionTask.Result(res.first, res.second.orEmpty())
    }

    private fun updateQuickReaction(params: UpdateQuickReactionTask.Params): Pair<String?, List<String>?> {
        // the emoji reaction has been selected, we need to check if we have reacted it or not
        val existingSummary = stores.annotations.get(params.eventId)
                ?: return Pair(params.reaction, null)
        val aggregationForReaction = existingSummary.reactionsSummary.firstOrNull { it.key == params.reaction }
        val aggregationForOppositeReaction = existingSummary.reactionsSummary.firstOrNull { it.key == params.oppositeReaction }

        return if (aggregationForReaction == null || !aggregationForReaction.addedByMe) {
            // i haven't yet reacted to it, so need to add it, but do I need to redact the opposite?
            val toRedact = aggregationForOppositeReaction?.sourceEvents?.mapNotNull {
                stores.event.getByEventId(it)?.takeIf { e -> e.sender == userId }?.eventId
            }
            Pair(params.reaction, toRedact)
        } else {
            // I already added it, so i need to undo it (like a toggle)
            val toRedact = aggregationForReaction.sourceEvents.mapNotNull {
                stores.event.getByEventId(it)?.takeIf { e -> e.sender == userId }?.eventId
            }
            Pair(null, toRedact)
        }
    }
}
