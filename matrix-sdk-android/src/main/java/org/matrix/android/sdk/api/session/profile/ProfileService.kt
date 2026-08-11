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
 *
 */

package org.matrix.android.sdk.api.session.profile

import kotlinx.coroutines.flow.Flow
import org.matrix.android.sdk.api.auth.UserInteractiveAuthInterceptor
import org.matrix.android.sdk.api.session.identity.ThreePid
import org.matrix.android.sdk.api.session.user.model.User
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.api.util.Optional

/**
 * This interface defines methods to handling profile information. It's implemented at the session level.
 */
interface ProfileService {

    companion object Constants {
        const val DISPLAY_NAME_KEY = ProfileKeys.DISPLAY_NAME
        const val AVATAR_URL_KEY = ProfileKeys.AVATAR_URL

        // MSC4427 profile banner / MSC4247 pronouns / MSC4175 time zone.
        // Written under both keys; read the stable one first.
        const val BANNER_URL_KEY = ProfileKeys.BANNER_URL
        const val BANNER_URL_KEY_UNSTABLE = ProfileKeys.BANNER_URL_UNSTABLE

        const val PRONOUNS_KEY = ProfileKeys.PRONOUNS
        const val PRONOUNS_KEY_UNSTABLE = ProfileKeys.PRONOUNS_UNSTABLE
        const val TIMEZONE_KEY = ProfileKeys.TIMEZONE
        const val TIMEZONE_KEY_UNSTABLE = ProfileKeys.TIMEZONE_UNSTABLE
    }

    /**
     * Return the current display name for this user.
     * @param userId the userId param to look for
     *
     */
    suspend fun getDisplayName(userId: String): Optional<String>

    /**
     * Update the display name for this user.
     * @param userId the userId to update the display name of
     * @param newDisplayName the new display name of the user
     */
    suspend fun setDisplayName(userId: String, newDisplayName: String)

    /**
     * Update the avatar for this user.
     * @param userId the userId to update the avatar of
     * @param newAvatarUri the new avatar uri of the user
     * @param fileName the fileName of selected image
     */
    suspend fun updateAvatar(userId: String, newAvatarUri: String, fileName: String)

    /**
     * Remove the avatar for this user.
     * @param userId the userId to delete the avatar of
     */
    suspend fun deleteAvatar(userId: String)

    /**
     * Return the current avatarUrl for this user.
     * @param userId the userId param to look for
     *
     */
    suspend fun getAvatarUrl(userId: String): Optional<String>

    /**
     * Set an arbitrary profile field for this user (MSC4133 extended profiles).
     */
    suspend fun setProfileField(userId: String, keyName: String, value: String)

    /**
     * Delete an arbitrary profile field for this user (MSC4133 extended profiles).
     */
    suspend fun deleteProfileField(userId: String, keyName: String)

    /**
     * Upload a banner image and set it as this user's profile banner (MSC4427).
     */
    suspend fun updateBanner(userId: String, newBannerUri: String, fileName: String)

    /**
     * Remove the profile banner for this user.
     */
    suspend fun deleteBanner(userId: String)

    /**
     * Return the current profile banner mxc url for this user.
     */
    suspend fun getBannerUrl(userId: String): Optional<String>

    /**
     * Last banner url this session has seen for this user (from any profile fetch or update),
     * or null when none is known. Synchronous, for seeding UI ahead of a network refresh.
     */
    fun getCachedBannerUrl(userId: String): String?

    /**
     * Set this user's pronouns (MSC4247). An empty list clears the field.
     */
    suspend fun setPronouns(userId: String, pronouns: List<Pronoun>)

    /**
     * Set this user's IANA time zone (MSC4175), e.g. "Europe/Paris". Blank clears the field.
     */
    suspend fun setTimezone(userId: String, timezone: String)

    /**
     * Set this user's status (MSC4426), e.g. "🌴 On holiday". Null clears the field.
     */
    suspend fun setStatus(userId: String, status: UserStatus?)

    /**
     * Set this user's biography (MSC4440). Null clears the field.
     */
    suspend fun setBio(userId: String, bio: UserBio?)

    /**
     * Pronouns/time zone/status/biography this session has last seen for this user, or null when not
     * yet known. Synchronous, for seeding UI and gendered notices ahead of a network refresh.
     */
    fun getCachedPronouns(userId: String): List<Pronoun>?

    fun getCachedTimezone(userId: String): String?

    fun getCachedStatus(userId: String): UserStatus?

    fun getCachedBio(userId: String): UserBio?

    /**
     * Emits a userId once that user's pronouns become known (from a background prefetch or fetch),
     * so views already rendered with the neutral fallback can rebuild with the gendered wording.
     */
    fun getPronounsUpdateFlow(): Flow<String>

    /**
     * Kick off a best-effort background fetch of this user's extended profile fields (pronouns/tz)
     * if not already cached, so a later [getCachedPronouns]/[getCachedTimezone] can succeed.
     */
    fun prefetchProfileFields(userId: String)

    /**
     * Get the combined profile information for this user.
     * This may return keys which are not limited to displayname or avatar_url.
     * If server is configured as limit_profile_requests_to_users_who_share_rooms: true then response can be HTTP 403.
     * @param userId the userId param to look for
     *
     */
    suspend fun getProfile(userId: String): JsonDict

    /**
     * Get the current user 3Pids.
     */
    fun getThreePids(): List<ThreePid>

    /**
     * Get the current user 3Pids Live.
     * @param refreshData set to true to fetch data from the homeserver
     */
    fun getThreePidsFlow(refreshData: Boolean): Flow<List<ThreePid>>

    /**
     * Get the pending 3Pids, i.e. ThreePids that have requested a token, but not yet validated by the user.
     */
    fun getPendingThreePids(): List<ThreePid>

    /**
     * Get the pending 3Pids Live.
     */
    fun getPendingThreePidsFlow(): Flow<List<ThreePid>>

    /**
     * Add a 3Pids. This is the first step to add a ThreePid to an account. Then the threePid will be added to the pending threePid list.
     */
    suspend fun addThreePid(threePid: ThreePid)

    /**
     * Validate a code received by text message.
     */
    suspend fun submitSmsCode(threePid: ThreePid.Msisdn, code: String)

    /**
     * Finalize adding a 3Pids. Call this method once the user has validated that he owns the ThreePid.
     */
    suspend fun finalizeAddingThreePid(
            threePid: ThreePid,
            userInteractiveAuthInterceptor: UserInteractiveAuthInterceptor
    )

    /**
     * Cancel adding a threepid. It will remove locally stored data about this ThreePid.
     */
    suspend fun cancelAddingThreePid(threePid: ThreePid)

    /**
     * Remove a 3Pid from the Matrix account.
     */
    suspend fun deleteThreePid(threePid: ThreePid)

    /**
     * Return a User object from a userId.
     */
    suspend fun getProfileAsUser(userId: String): User {
        return getProfile(userId).let { dict ->
            User(
                    userId = userId,
                    displayName = dict[DISPLAY_NAME_KEY] as? String,
                    avatarUrl = dict[AVATAR_URL_KEY] as? String
            )
        }
    }
}
