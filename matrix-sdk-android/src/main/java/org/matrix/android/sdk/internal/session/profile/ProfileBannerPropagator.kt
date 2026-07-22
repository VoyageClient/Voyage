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

package org.matrix.android.sdk.internal.session.profile

import dagger.Lazy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.failure.getRetryDelay
import org.matrix.android.sdk.api.failure.isLimitExceededError
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.profile.ProfileService
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.room.model.localecho.RoomLocalEcho
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.room.RoomGetter
import org.matrix.android.sdk.internal.task.TaskExecutor
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

internal fun JsonDict.profileBannerUrl(): String? {
    return (this[ProfileService.BANNER_URL_KEY_UNSTABLE] as? String)
            ?: (this[ProfileService.BANNER_URL_KEY] as? String)
}

/**
 * Homeservers propagate displayname/avatar_url into the user's m.room.member event in every joined
 * room when the profile changes, and stamp them into the membership event of every room joined
 * (CS spec, "Events on Change of Profile Information"). They know nothing about MSC4427 banners,
 * so this mirrors both behaviors client-side.
 */
@SessionScope
internal class ProfileBannerPropagator @Inject constructor(
        @UserId private val myUserId: String,
        private val taskExecutor: TaskExecutor,
        @SessionDatabase private val database: SessionSqlDatabase,
        private val getProfileInfoTask: GetProfileInfoTask,
        private val roomGetter: Lazy<RoomGetter>,
) {

    // Last banner url seen per user, so UIs can seed synchronously instead of popping in after a fetch
    private val bannerUrlCache = ConcurrentHashMap<String, Optional<String>>()

    fun getCachedBannerUrl(userId: String): String? = bannerUrlCache[userId]?.getOrNull()

    fun cacheBannerUrl(userId: String, bannerUrl: String?) {
        bannerUrlCache[userId] = Optional.from(bannerUrl)
    }

    // Own profile only, sequential and best-effort per room in the background, like the server's
    // avatar propagation. Also like the server's version, the re-issued membership event resets
    // per-room displayname/avatar/banner overrides to the account-wide values: displayname and
    // avatar_url are omitted so the server re-fills them from the global profile.
    fun propagateToJoinedRooms(userId: String, newBannerUrl: String?) {
        if (userId != myUserId) return
        taskExecutor.executorScope.launch {
            // Globals are fetched only for the skip-if-up-to-date check; the events themselves
            // rely on the server's re-fill, which can't go stale.
            val profile = tryOrNull { getProfileInfoTask.execute(GetProfileInfoTask.Params(myUserId)) }
            val globalDisplayName = profile?.get(ProfileService.DISPLAY_NAME_KEY) as? String
            val globalAvatarUrl = profile?.get(ProfileService.AVATAR_URL_KEY) as? String
            database.roomSummaryQueries.selectAll().executeAsList()
                    .filter { it.membership_str == Membership.JOIN.name && !RoomLocalEcho.isLocalEchoId(it.room_id) }
                    .forEach { summary ->
                        runCatching {
                            val room = roomGetter.get().getRoom(summary.room_id) ?: return@runCatching
                            val current = currentMemberContent(room)
                            val upToDate = current?.bannerUrl == newBannerUrl &&
                                    (profile == null || (current?.displayName == globalDisplayName && current?.avatarUrl == globalAvatarUrl))
                            if (upToDate) return@runCatching
                            withRateLimitRetry { room.stateService().updateMyRoomProfile(null, null, newBannerUrl) }
                        }.onFailure {
                            Timber.w(it, "Failed to propagate banner to member event in ${summary.room_id}")
                        }
                    }
        }
    }

    // Call once a joined/created room has synced: the fresh server-built membership event carries
    // our displayname/avatar but never a banner, so only the banner needs stamping.
    fun stampBannerOnJoin(roomId: String) {
        taskExecutor.executorScope.launch {
            val cached = bannerUrlCache[myUserId]
            val banner = (if (cached != null) cached.getOrNull() else fetchOwnBanner()) ?: return@launch
            runCatching {
                val room = roomGetter.get().getRoom(roomId) ?: return@runCatching
                if (currentMemberContent(room)?.bannerUrl == banner) return@runCatching
                withRateLimitRetry { room.stateService().resetMyRoomBanner(banner) }
            }.onFailure {
                Timber.w(it, "Failed to stamp banner into member event in $roomId")
            }
        }
    }

    private suspend fun fetchOwnBanner(): String? {
        val profile = tryOrNull { getProfileInfoTask.execute(GetProfileInfoTask.Params(myUserId)) } ?: return null
        return profile.profileBannerUrl().also { cacheBannerUrl(myUserId, it) }
    }

    private fun currentMemberContent(room: Room): RoomMemberContent? {
        return room.stateService()
                .getStateEvent(EventType.STATE_ROOM_MEMBER, QueryStringValue.Equals(myUserId))
                ?.content?.toModel<RoomMemberContent>()
    }

    // Bounded so a persistently rate-limiting server can't pin the room-by-room loop forever.
    private suspend fun withRateLimitRetry(block: suspend () -> Unit) {
        repeat(4) {
            try {
                return block()
            } catch (failure: Throwable) {
                if (failure.isLimitExceededError()) delay(failure.getRetryDelay(1_000L)) else throw failure
            }
        }
        block()
    }
}
