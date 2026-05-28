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
     * app restarts. Returns null on fetch failure.
     */
    suspend fun ensureEventCached(
            roomId: String,
            eventId: String
    ): Event?
}
