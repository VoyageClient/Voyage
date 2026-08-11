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

package org.matrix.android.sdk.internal.session.profile

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.auth.UserInteractiveAuthInterceptor
import org.matrix.android.sdk.api.session.identity.ThreePid
import org.matrix.android.sdk.api.session.profile.ProfileKeys
import org.matrix.android.sdk.api.session.profile.ProfileService
import org.matrix.android.sdk.api.session.profile.Pronoun
import org.matrix.android.sdk.api.session.profile.UserBio
import org.matrix.android.sdk.api.session.profile.UserStatus
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.api.util.MimeTypes
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.internal.database.model.UserThreePidEntity
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.content.FileUploader
import org.matrix.android.sdk.internal.session.user.UserStore
import org.matrix.android.sdk.internal.task.TaskExecutor
import org.matrix.android.sdk.internal.task.configureWith
import timber.log.Timber
import javax.inject.Inject

internal class DefaultProfileService @Inject constructor(
        private val taskExecutor: TaskExecutor,
        @SessionDatabase private val database: org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase,
        @SessionDatabase private val dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        private val stores: org.matrix.android.sdk.internal.database.sql.store.SessionStores,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val refreshUserThreePidsTask: RefreshUserThreePidsTask,
        private val getProfileInfoTask: GetProfileInfoTask,
        private val setDisplayNameTask: SetDisplayNameTask,
        private val setAvatarUrlTask: SetAvatarUrlTask,
        private val setProfileFieldTask: SetProfileFieldTask,
        private val deleteProfileFieldTask: DeleteProfileFieldTask,
        private val addThreePidTask: AddThreePidTask,
        private val validateSmsCodeTask: ValidateSmsCodeTask,
        private val finalizeAddingThreePidTask: FinalizeAddingThreePidTask,
        private val deleteThreePidTask: DeleteThreePidTask,
        private val pendingThreePidMapper: PendingThreePidMapper,
        private val userStore: UserStore,
        private val fileUploader: FileUploader,
        private val extendedProfileCache: ExtendedProfileCache
) : ProfileService {

    override suspend fun getDisplayName(userId: String): Optional<String> {
        val params = GetProfileInfoTask.Params(userId)
        val data = getProfileInfoTask.execute(params)
        val displayName = data[ProfileService.DISPLAY_NAME_KEY] as? String
        return Optional.from(displayName)
    }

    override suspend fun setDisplayName(userId: String, newDisplayName: String) {
        withContext(coroutineDispatchers.io) {
            setDisplayNameTask.execute(SetDisplayNameTask.Params(userId = userId, newDisplayName = newDisplayName))
            userStore.updateDisplayName(userId, newDisplayName)
        }
    }

    override suspend fun updateAvatar(userId: String, newAvatarUri: String, fileName: String) {
        val response = fileUploader.uploadFromUri(newAvatarUri, fileName, MimeTypes.Jpeg)
        setAvatarUrlTask.execute(SetAvatarUrlTask.Params(userId = userId, newAvatarUrl = response.contentUri))
        userStore.updateAvatar(userId, response.contentUri)
    }

    override suspend fun deleteAvatar(userId: String) {
        setAvatarUrlTask.execute(SetAvatarUrlTask.Params(userId = userId, newAvatarUrl = ""))
        userStore.updateAvatar(userId, "")
    }

    override suspend fun getAvatarUrl(userId: String): Optional<String> {
        val params = GetProfileInfoTask.Params(userId)
        val data = getProfileInfoTask.execute(params)
        val avatarUrl = data[ProfileService.AVATAR_URL_KEY] as? String
        return Optional.from(avatarUrl)
    }

    override suspend fun setProfileField(userId: String, keyName: String, value: String) {
        setProfileFieldTask.execute(SetProfileFieldTask.Params(userId = userId, keyName = keyName, value = value))
    }

    override suspend fun deleteProfileField(userId: String, keyName: String) {
        deleteProfileFieldTask.execute(DeleteProfileFieldTask.Params(userId = userId, keyName = keyName))
    }

    override suspend fun updateBanner(userId: String, newBannerUri: String, fileName: String) {
        val response = fileUploader.uploadFromUri(newBannerUri, fileName, MimeTypes.Jpeg)
        setProfileFieldBothKeys(userId, ProfileService.BANNER_URL_KEY, ProfileService.BANNER_URL_KEY_UNSTABLE, response.contentUri)
        extendedProfileCache.cacheBannerUrl(userId, response.contentUri)
    }

    override suspend fun deleteBanner(userId: String) {
        deleteProfileFieldBothKeys(userId, ProfileService.BANNER_URL_KEY, ProfileService.BANNER_URL_KEY_UNSTABLE)
        extendedProfileCache.cacheBannerUrl(userId, null)
    }

    override suspend fun getBannerUrl(userId: String): Optional<String> {
        val data = getProfileInfoTask.execute(GetProfileInfoTask.Params(userId))
        return Optional.from(data.profileBannerUrl()).also {
            extendedProfileCache.cacheBannerUrl(userId, it.getOrNull())
        }
    }

    override fun getCachedBannerUrl(userId: String): String? {
        return extendedProfileCache.getCachedBannerUrl(userId)
    }

    override suspend fun setPronouns(userId: String, pronouns: List<Pronoun>) {
        if (pronouns.isEmpty()) {
            deleteProfileFieldBothKeys(userId, ProfileService.PRONOUNS_KEY, ProfileService.PRONOUNS_KEY_UNSTABLE)
        } else {
            setProfileFieldBothKeys(userId, ProfileService.PRONOUNS_KEY, ProfileService.PRONOUNS_KEY_UNSTABLE, pronouns.toProfileValue())
        }
        extendedProfileCache.cachePronouns(userId, pronouns)
    }

    override suspend fun setTimezone(userId: String, timezone: String) {
        if (timezone.isBlank()) {
            deleteProfileFieldBothKeys(userId, ProfileService.TIMEZONE_KEY, ProfileService.TIMEZONE_KEY_UNSTABLE)
            extendedProfileCache.cacheTimezone(userId, null)
        } else {
            setProfileFieldBothKeys(userId, ProfileService.TIMEZONE_KEY, ProfileService.TIMEZONE_KEY_UNSTABLE, timezone)
            extendedProfileCache.cacheTimezone(userId, timezone)
        }
    }

    override suspend fun setStatus(userId: String, status: UserStatus?) {
        val cleared = status?.takeIf { !it.isEmpty() }
        if (cleared == null) {
            deleteProfileFieldKeys(userId, STATUS_KEYS)
        } else {
            setProfileFieldKeys(userId, cleared.toProfileValues())
        }
        extendedProfileCache.cacheStatus(userId, cleared)
    }

    override suspend fun setBio(userId: String, bio: UserBio?) {
        val cleared = bio?.takeIf { !it.isEmpty() }
        if (cleared == null) {
            deleteProfileFieldKeys(userId, BIOGRAPHY_KEYS)
        } else {
            setProfileFieldKeys(userId, cleared.toProfileValues())
        }
        extendedProfileCache.cacheBio(userId, cleared)
    }

    override fun getCachedStatus(userId: String): UserStatus? = extendedProfileCache.getCachedStatus(userId)

    override fun getCachedBio(userId: String): UserBio? = extendedProfileCache.getCachedBio(userId)

    // MSC4133 is one request per key, so a field written under several prefixes is several requests.
    // A server that rejects one key (unknown/too many fields) must not fail the whole update while
    // the others landed.
    private suspend fun setProfileFieldBothKeys(userId: String, stableKey: String, unstableKey: String, value: Any) {
        setProfileFieldKeys(userId, mapOf(stableKey to value, unstableKey to value))
    }

    private suspend fun setProfileFieldKeys(userId: String, values: Map<String, Any>) {
        eachKey(values.keys) { key ->
            setProfileFieldTask.execute(SetProfileFieldTask.Params(userId, key, values.getValue(key)))
        }
    }

    private suspend fun deleteProfileFieldBothKeys(userId: String, stableKey: String, unstableKey: String) {
        deleteProfileFieldKeys(userId, setOf(stableKey, unstableKey))
    }

    private suspend fun deleteProfileFieldKeys(userId: String, keys: Set<String>) {
        eachKey(keys) { key ->
            deleteProfileFieldTask.execute(DeleteProfileFieldTask.Params(userId, key))
        }
    }

    private suspend fun eachKey(keys: Set<String>, block: suspend (String) -> Unit) {
        val failures = keys.mapNotNull { key ->
            runCatching { block(key) }.exceptionOrNull()?.also { Timber.w(it, "Failed to write profile field $key") }
        }
        if (failures.size == keys.size) throw failures.first()
    }

    override fun getCachedPronouns(userId: String): List<Pronoun>? = extendedProfileCache.getCachedPronouns(userId)

    override fun getCachedTimezone(userId: String): String? = extendedProfileCache.getCachedTimezone(userId)

    override fun prefetchProfileFields(userId: String) = extendedProfileCache.prefetch(userId)

    override fun getPronounsUpdateFlow() = extendedProfileCache.pronounsUpdateFlow

    override suspend fun getProfile(userId: String): JsonDict {
        val params = GetProfileInfoTask.Params(userId)
        return getProfileInfoTask.execute(params).also {
            extendedProfileCache.cacheFromProfile(userId, it)
        }
    }

    override fun getThreePids(): List<ThreePid> {
        return stores.threePid.getThreePids().map { it.asDomain() }
    }

    override fun getThreePidsFlow(refreshData: Boolean): Flow<List<ThreePid>> {
        if (refreshData) {
            // Force a refresh of the values
            refreshThreePids()
        }
        return database.userThreePidQueries.selectAll().asFlow().mapToList(dispatcher)
                .map { stores.threePid.getThreePids().map { entity -> entity.asDomain() } }
    }

    private fun refreshThreePids() {
        refreshUserThreePidsTask
                .configureWith()
                .executeBy(taskExecutor)
    }

    override fun getPendingThreePids(): List<ThreePid> {
        return stores.threePid.getPendingThreePids().map { pendingThreePidMapper.map(it).threePid }
    }

    override fun getPendingThreePidsFlow(): Flow<List<ThreePid>> {
        return database.pendingThreePidQueries.selectAll().asFlow().mapToList(dispatcher)
                .map { stores.threePid.getPendingThreePids().map { entity -> pendingThreePidMapper.map(entity).threePid } }
    }

    override suspend fun addThreePid(threePid: ThreePid) {
        addThreePidTask.execute(AddThreePidTask.Params(threePid))
    }

    override suspend fun submitSmsCode(threePid: ThreePid.Msisdn, code: String) {
        validateSmsCodeTask.execute(ValidateSmsCodeTask.Params(threePid, code))
    }

    override suspend fun finalizeAddingThreePid(
            threePid: ThreePid,
            userInteractiveAuthInterceptor: UserInteractiveAuthInterceptor
    ) {
        finalizeAddingThreePidTask
                .execute(
                        FinalizeAddingThreePidTask.Params(
                                threePid = threePid,
                                userInteractiveAuthInterceptor = userInteractiveAuthInterceptor,
                                userWantsToCancel = false
                        )
                )
        refreshThreePids()
    }

    override suspend fun cancelAddingThreePid(threePid: ThreePid) {
        finalizeAddingThreePidTask
                .execute(
                        FinalizeAddingThreePidTask.Params(
                                threePid = threePid,
                                userInteractiveAuthInterceptor = null,
                                userWantsToCancel = true
                        )
                )
        refreshThreePids()
    }

    override suspend fun deleteThreePid(threePid: ThreePid) {
        deleteThreePidTask.execute(DeleteThreePidTask.Params(threePid))
        refreshThreePids()
    }

    companion object {
        private val STATUS_KEYS = setOf(ProfileKeys.STATUS, ProfileKeys.STATUS_UNSTABLE, ProfileKeys.STATUS_COMMET)
        private val BIOGRAPHY_KEYS = setOf(ProfileKeys.BIOGRAPHY, ProfileKeys.BIOGRAPHY_UNSTABLE, ProfileKeys.BIOGRAPHY_COMMET)
    }
}

private fun UserThreePidEntity.asDomain(): ThreePid {
    return when (medium) {
        ThirdPartyIdentifier.MEDIUM_EMAIL -> ThreePid.Email(address)
        ThirdPartyIdentifier.MEDIUM_MSISDN -> ThreePid.Msisdn(address)
        else -> error("Invalid medium type")
    }
}
