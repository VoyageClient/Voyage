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

package org.matrix.android.sdk.api.session.room.send

import kotlinx.coroutines.flow.Flow
import org.matrix.android.sdk.api.util.Optional

interface DraftService {

    /**
     * Save or update a draft to the room.
     */
    suspend fun saveDraft(draft: UserDraft)

    /**
     * Save the room's drafts, the active one last: an edit in progress sits on top of the message the
     * user was writing when they started it, and that message comes back when the edit ends.
     */
    suspend fun saveDrafts(drafts: List<UserDraft>)

    /**
     * Delete the last draft, basically just after sending the message.
     */
    suspend fun deleteDraft()

    /**
     * Return the active draft or null.
     */
    fun getDraft(): UserDraft?

    /**
     * Return every draft of the room, the active one last. See [saveDrafts].
     */
    fun getDrafts(): List<UserDraft>

    /**
     * Return the current draft if any, as a live data.
     */
    fun getDraftFlow(): Flow<Optional<UserDraft>>
}
