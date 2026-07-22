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

package org.matrix.android.sdk.internal.session.room.state

import android.net.Uri
import androidx.lifecycle.LiveData
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.matrix.android.sdk.api.query.QueryStateEventValue
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.BannerImageInfo
import org.matrix.android.sdk.api.session.room.model.GuestAccess
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomBannerContent
import org.matrix.android.sdk.api.session.room.model.RoomCanonicalAliasContent
import org.matrix.android.sdk.api.session.room.model.RoomHistoryVisibility
import org.matrix.android.sdk.api.session.room.model.RoomJoinRules
import org.matrix.android.sdk.api.session.room.model.RoomJoinRulesAllowEntry
import org.matrix.android.sdk.api.session.room.model.RoomJoinRulesContent
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.room.powerlevels.RoomPowerLevels
import org.matrix.android.sdk.api.session.room.state.StateService
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.api.util.MimeTypes
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.content.FileUploader
import org.matrix.android.sdk.internal.session.room.powerlevels.getRoomPowerLevels
import org.matrix.android.sdk.internal.session.room.powerlevels.getRoomPowerLevelsLive

internal class DefaultStateService @AssistedInject constructor(
        @Assisted private val roomId: String,
        @UserId private val userId: String,
        private val stateEventDataSource: StateEventDataSource,
        private val sendStateTask: SendStateTask,
        private val fileUploader: FileUploader,
) : StateService {

    @AssistedFactory
    interface Factory {
        fun create(roomId: String): DefaultStateService
    }

    override fun getStateEvent(eventType: String, stateKey: QueryStateEventValue): Event? {
        return stateEventDataSource.getStateEvent(roomId, eventType, stateKey)
    }

    override fun getStateEventLive(eventType: String, stateKey: QueryStateEventValue): LiveData<Optional<Event>> {
        return stateEventDataSource.getStateEventLive(roomId, eventType, stateKey)
    }

    override fun getStateEvents(eventTypes: Set<String>, stateKey: QueryStateEventValue): List<Event> {
        return stateEventDataSource.getStateEvents(roomId, eventTypes, stateKey)
    }

    override fun getStateEventsLive(eventTypes: Set<String>, stateKey: QueryStateEventValue): LiveData<List<Event>> {
        return stateEventDataSource.getStateEventsLive(roomId, eventTypes, stateKey)
    }

    override fun getRoomPowerLevels(): RoomPowerLevels {
        return stateEventDataSource.getRoomPowerLevels(roomId)
    }

    override fun getRoomPowerLevelsLive(): LiveData<RoomPowerLevels> {
        return stateEventDataSource.getRoomPowerLevelsLive(roomId)
    }

    override suspend fun sendStateEvent(
            eventType: String,
            stateKey: String,
            body: JsonDict
    ): String {
        val params = SendStateTask.Params(
                roomId = roomId,
                stateKey = stateKey,
                eventType = eventType,
                body = body.toSafeJson(eventType)
        )
        return sendStateTask.executeRetry(params, 3)
    }

    private fun JsonDict.toSafeJson(eventType: String): JsonDict {
        // Safe treatment for PowerLevelContent
        return when (eventType) {
            EventType.STATE_ROOM_POWER_LEVELS -> toSafePowerLevelsContentDict()
            else -> this
        }
    }

    override suspend fun updateTopic(topic: String) {
        sendStateEvent(
                eventType = EventType.STATE_ROOM_TOPIC,
                body = mapOf("topic" to topic),
                stateKey = ""
        )
    }

    override suspend fun updateName(name: String) {
        sendStateEvent(
                eventType = EventType.STATE_ROOM_NAME,
                body = mapOf("name" to name),
                stateKey = ""
        )
    }

    override suspend fun updateCanonicalAlias(alias: String?, altAliases: List<String>) {
        sendStateEvent(
                eventType = EventType.STATE_ROOM_CANONICAL_ALIAS,
                body = RoomCanonicalAliasContent(
                        canonicalAlias = alias,
                        alternativeAliases = altAliases
                                // Ensure there is no duplicate
                                .distinct()
                                // Ensure the canonical alias is not also included in the alt alias
                                .minus(listOfNotNull(alias))
                                // Sort for the cleanup
                                .sorted()
                ).toContent(),
                stateKey = ""
        )
    }

    override suspend fun updateHistoryReadability(readability: RoomHistoryVisibility) {
        sendStateEvent(
                eventType = EventType.STATE_ROOM_HISTORY_VISIBILITY,
                body = mapOf("history_visibility" to readability),
                stateKey = ""
        )
    }

    override suspend fun updateJoinRule(joinRules: RoomJoinRules?, guestAccess: GuestAccess?, allowList: List<RoomJoinRulesAllowEntry>?) {
        if (joinRules != null) {
            val body = if (joinRules == RoomJoinRules.RESTRICTED) {
                RoomJoinRulesContent(
                        joinRulesStr = RoomJoinRules.RESTRICTED.value,
                        allowList = allowList
                ).toContent()
            } else {
                mapOf("join_rule" to joinRules)
            }
            sendStateEvent(
                    eventType = EventType.STATE_ROOM_JOIN_RULES,
                    body = body,
                    stateKey = ""
            )
        }
        if (guestAccess != null) {
            sendStateEvent(
                    eventType = EventType.STATE_ROOM_GUEST_ACCESS,
                    body = mapOf("guest_access" to guestAccess),
                    stateKey = ""
            )
        }
    }

    override suspend fun updateAvatar(avatarUri: Uri, fileName: String) {
        val response = fileUploader.uploadFromUri(avatarUri, fileName, MimeTypes.Jpeg)
        sendStateEvent(
                eventType = EventType.STATE_ROOM_AVATAR,
                body = mapOf("url" to response.contentUri),
                stateKey = ""
        )
    }

    override suspend fun deleteAvatar() {
        sendStateEvent(
                eventType = EventType.STATE_ROOM_AVATAR,
                body = emptyMap(),
                stateKey = ""
        )
    }

    override suspend fun updateBanner(bannerUri: Uri, fileName: String) {
        val response = fileUploader.uploadFromUri(bannerUri, fileName, MimeTypes.Jpeg)
        sendStateEvent(
                eventType = EventType.STATE_ROOM_BANNER.unstable,
                body = RoomBannerContent(
                        url = response.contentUri,
                        info = BannerImageInfo(mimeType = MimeTypes.Jpeg)
                ).toContent(),
                stateKey = ""
        )
    }

    override suspend fun deleteBanner() {
        sendStateEvent(
                eventType = EventType.STATE_ROOM_BANNER.unstable,
                body = emptyMap(),
                stateKey = ""
        )
    }

    // A null displayname/avatar_url is omitted from the serialized event, and Synapse re-fills
    // omitted profile fields on join membership events from the account-wide profile. So null means
    // "reset to the account-wide value", while an explicit "" blanks the field (omitting can't:
    // the server would re-fill it).

    override suspend fun updateMyRoomDisplayName(displayName: String?) {
        sendMyRoomMemberContent { copy(displayName = displayName) }
    }

    override suspend fun updateMyRoomAvatar(avatarUri: Uri, fileName: String) {
        val response = fileUploader.uploadFromUri(avatarUri, fileName, MimeTypes.Jpeg)
        sendMyRoomMemberContent { copy(avatarUrl = response.contentUri) }
    }

    override suspend fun resetMyRoomAvatar(avatarUrl: String?) {
        sendMyRoomMemberContent { copy(avatarUrl = avatarUrl) }
    }

    override suspend fun updateMyRoomBanner(bannerUri: Uri, fileName: String) {
        val response = fileUploader.uploadFromUri(bannerUri, fileName, MimeTypes.Jpeg)
        sendMyRoomMemberContent { copy(bannerUrl = response.contentUri) }
    }

    override suspend fun resetMyRoomBanner(bannerUrl: String?) {
        sendMyRoomMemberContent { copy(bannerUrl = bannerUrl) }
    }

    override suspend fun updateMyRoomProfile(displayName: String?, avatarUrl: String?, bannerUrl: String?) {
        sendMyRoomMemberContent { copy(displayName = displayName, avatarUrl = avatarUrl, bannerUrl = bannerUrl) }
    }

    private suspend fun sendMyRoomMemberContent(transform: RoomMemberContent.() -> RoomMemberContent) {
        val rawContent = getStateEvent(EventType.STATE_ROOM_MEMBER, QueryStringValue.Equals(userId))?.content
        val currentContent = rawContent?.toModel<RoomMemberContent>()
                ?: RoomMemberContent(membership = Membership.JOIN)
        val oldTyped = currentContent.toContent()
        val newTyped = currentContent.transform().toContent()
        // Apply only the keys the transform changed onto the raw content, so the round-trip neither
        // drops keys the model doesn't know nor materializes model defaults (e.g. is_direct: false)
        // into events that never carried them.
        val body = if (rawContent == null) {
            newTyped
        } else {
            rawContent.toMutableMap().apply {
                (oldTyped.keys + newTyped.keys).forEach { key ->
                    when {
                        key !in newTyped -> remove(key)
                        oldTyped[key] != newTyped[key] -> put(key, newTyped.getValue(key))
                    }
                }
            }
        }
        sendStateEvent(
                eventType = EventType.STATE_ROOM_MEMBER,
                body = body,
                stateKey = userId
        )
    }

    override suspend fun setJoinRulePublic() {
        updateJoinRule(RoomJoinRules.PUBLIC, null)
    }

    override suspend fun setJoinRuleInviteOnly() {
        updateJoinRule(RoomJoinRules.INVITE, null)
    }

    override suspend fun setJoinRuleKnock() {
        updateJoinRule(RoomJoinRules.KNOCK, null)
    }

    override suspend fun setJoinRuleRestricted(allowList: List<String>) {
        // we need to compute correct via parameters and check if PL are correct
        val allowEntries = allowList.map { spaceId ->
            RoomJoinRulesAllowEntry.restrictedToRoom(spaceId)
        }
        updateJoinRule(RoomJoinRules.RESTRICTED, null, allowEntries)
    }
}
