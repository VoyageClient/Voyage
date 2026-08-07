/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.database.model

internal open class ChunkEntity(
        var prevToken: String? = null,
        // Because of gaps we can have several chunks with nextToken == null
        var nextToken: String? = null,
        var prevChunk: ChunkEntity? = null,
        var nextChunk: ChunkEntity? = null,
        var stateEvents: MutableList<EventEntity> = ArrayList(),
        var timelineEvents: MutableList<TimelineEventEntity> = ArrayList(),
        // Only one chunk will have isLastForward == true
        var isLastForward: Boolean = false,
        var isLastBackward: Boolean = false,
        // Threads
        var rootThreadEventId: String? = null,
        var isLastForwardThread: Boolean = false,
) {

    fun identifier() = "${prevToken}_$nextToken"

    // If true, then this chunk was previously a last forward chunk
    fun hasBeenALastForwardChunk() = nextToken == null && !isLastForward

    companion object
}

/**
 * Delete the chunk along with the thread events that were temporarily created.
 */
