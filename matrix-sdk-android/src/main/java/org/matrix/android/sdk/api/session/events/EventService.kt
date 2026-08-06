/*
 * Copyright (c) 2021 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.api.session.events

import kotlinx.coroutines.flow.Flow
import org.matrix.android.sdk.api.session.events.model.Event

interface EventService {

    /**
     * Ask the homeserver for an event content. The SDK will try to decrypt it if it is possible
     * The result will not be stored into cache
     */
    suspend fun getEvent(
            roomId: String,
            eventId: String
    ): Event

    /**
     * MSC2815: ask the homeserver for an event's pre-redaction content. Requires a power level at or
     * above the room's `redact` level, or server-admin rights, and a server advertising
     * `fi.mau.msc2815`. The result is never written to the event cache — persisting it would only
     * have it pruned again by the redaction processor.
     *
     * Throws [org.matrix.android.sdk.api.failure.Failure.ServerError] with
     * [org.matrix.android.sdk.api.failure.MatrixError.M_UNREDACTED_CONTENT_DELETED] once the server's
     * retention period has elapsed, or [org.matrix.android.sdk.api.failure.MatrixError.M_FORBIDDEN]
     * when the caller lacks permission.
     */
    suspend fun getUnredactedEvent(
            roomId: String,
            eventId: String
    ): Event

    /**
     * Get an Event from cache. Return null if not found.
     */
    fun getEventFromCache(
            roomId: String,
            eventId: String
    ): Event?

    /**
     * Return the event from cache if present, otherwise fetch it from the homeserver and
     * persist it to the local event cache so subsequent calls to [getEventFromCache] return it.
     *
     * Use this for ancillary lookups (e.g. resolving the target of an `m.in_reply_to` whose
     * referenced event isn't in the timeline DB) where you want the event to survive across
     * app restarts. On fetch failure returns the cached event if there is one, else null.
     *
     * [requireTimelineEvent] also guarantees a timeline entry resolvable via
     * [org.matrix.android.sdk.api.session.room.timeline.TimelineService.getTimelineEvent]: a gappy
     * sync clears a room's chunks but keeps event rows, so a cached event alone doesn't imply one.
     */
    suspend fun ensureEventCached(
            roomId: String,
            eventId: String,
            requireTimelineEvent: Boolean = false
    ): Event?

    /**
     * Ask for [event] (still `m.room.encrypted`) to be decrypted in the background and the result
     * persisted to the event cache. No-op for non-encrypted events; if the key is missing, the
     * attempt is retried automatically when it arrives. Completion surfaces via [decryptionUpdates].
     */
    fun requestDecryption(event: Event)

    /**
     * Emits whenever an event in [roomId] is decrypted and persisted, by any decryptor. Screens
     * showing individual events outside a Timeline must re-read them on this signal, since
     * decryption rewrites the event row without touching what timeline-event flows observe.
     */
    fun decryptionUpdates(roomId: String): Flow<Unit>
}
