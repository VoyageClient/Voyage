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

package org.matrix.android.sdk.api.session.room.state

import kotlinx.coroutines.flow.Flow
import org.matrix.android.sdk.api.query.QueryStateEventValue
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.profile.ColorPreference
import org.matrix.android.sdk.api.session.room.model.GuestAccess
import org.matrix.android.sdk.api.session.room.model.RoomHistoryVisibility
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.session.room.model.RoomJoinRulesAllowEntry
import org.matrix.android.sdk.api.session.room.powerlevels.RoomPowerLevels
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.api.util.Optional

interface StateService {

    /**
     * Update the topic of the room. [formattedTopic] is the HTML rendering to publish alongside the
     * plain text (MSC3765 extensible topics); pass null for a plain-text-only topic.
     */
    suspend fun updateTopic(topic: String, formattedTopic: String? = null)

    /**
     * Update the name of the room.
     */
    suspend fun updateName(name: String)

    /**
     * Update the canonical alias of the room.
     * @param alias the canonical alias, or null to reset the canonical alias of this room
     * @param altAliases the alternative aliases for this room. It should include the canonical alias if any.
     */
    suspend fun updateCanonicalAlias(alias: String?, altAliases: List<String>)

    /**
     * Update the history readability of the room.
     */
    suspend fun updateHistoryReadability(readability: RoomHistoryVisibility)

    /**
     * Update the join rule and/or the guest access.
     */
    suspend fun updateJoinRule(joinRules: RoomJoinRules?, guestAccess: GuestAccess?, allowList: List<RoomJoinRulesAllowEntry>? = null)

    /**
     * Update the avatar of the room.
     */
    suspend fun updateAvatar(avatarUri: String, fileName: String)

    /**
     * Delete the avatar of the room.
     */
    suspend fun deleteAvatar()

    /**
     * Update the banner of the room (MSC4221).
     */
    suspend fun updateBanner(bannerUri: String, fileName: String)

    /**
     * Delete the banner of the room.
     */
    suspend fun deleteBanner()

    /**
     * Update the current user's display name for this room only (the self m.room.member event).
     * null omits the field so the server restores the account-wide name; "" blanks it explicitly.
     */
    suspend fun updateMyRoomDisplayName(displayName: String?)

    /**
     * Upload an avatar and set it as the current user's avatar for this room only (the self m.room.member event).
     */
    suspend fun updateMyRoomAvatar(avatarUri: String, fileName: String)

    /**
     * Set the current user's avatar for this room only (the self m.room.member event).
     * null omits the field so the server restores the account-wide avatar; "" removes it explicitly.
     */
    suspend fun resetMyRoomAvatar(avatarUrl: String?)

    /**
     * Set the current user's display name and avatar for this room only, in a single self
     * m.room.member event. Same null / "" semantics as the single-field variants.
     */
    suspend fun updateMyRoomProfile(displayName: String?, avatarUrl: String?)

    /**
     * Set the current user's MSC4522 name color for this room only. Null removes the field so the
     * account-wide color applies again.
     */
    suspend fun updateMyRoomColorPreference(color: ColorPreference?)

    /** Drop this room's display name, avatar and name color overrides from the self m.room.member event. */
    suspend fun resetMyRoomProfile()

    /**
     * Send a state event to the room.
     * @param eventType The type of event to send.
     * @param stateKey The state_key for the state to send. Can be an empty string.
     * @param body The content object of the event; the fields in this object will vary depending on the type of event
     * @return the id of the created state event
     */
    suspend fun sendStateEvent(eventType: String, stateKey: String, body: JsonDict): String

    /**
     * Get a state event of the room.
     * @param eventType An eventType.
     * @param stateKey the query which will be done on the stateKey
     */
    fun getStateEvent(eventType: String, stateKey: QueryStateEventValue): Event?

    /**
     * Get a live state event of the room.
     * @param eventType An eventType.
     * @param stateKey the query which will be done on the stateKey
     */
    fun getStateEventFlow(eventType: String, stateKey: QueryStateEventValue): Flow<Optional<Event>>

    /**
     * Get state events of the room.
     * @param eventTypes Set of eventType. If empty, all state events will be returned
     * @param stateKey the query which will be done on the stateKey
     */
    fun getStateEvents(eventTypes: Set<String>, stateKey: QueryStateEventValue): List<Event>

    /**
     * Get live state events of the room.
     * @param eventTypes Set of eventType to observe. If empty, all state events will be observed
     * @param stateKey the query which will be done on the stateKey
     */
    fun getStateEventsFlow(eventTypes: Set<String>, stateKey: QueryStateEventValue): Flow<List<Event>>

    suspend fun setJoinRulePublic()
    suspend fun setJoinRuleInviteOnly()
    suspend fun setJoinRuleKnock()
    suspend fun setJoinRuleRestricted(allowList: List<String>)
    suspend fun setJoinRuleKnockRestricted(allowList: List<String>)
    fun getRoomPowerLevels(): RoomPowerLevels
    fun getRoomPowerLevelsFlow(): Flow<RoomPowerLevels>
}
