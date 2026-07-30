/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.session

import android.content.Context
import androidx.lifecycle.asFlow
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.core.extensions.startSyncing
import im.vector.app.core.notification.NotificationsSettingUpdater
import im.vector.app.core.notification.PushRulesUpdater
import im.vector.app.core.session.clientinfo.UpdateMatrixClientInfoUseCase
import im.vector.app.features.reactions.data.QuickReactionsDataSource
import im.vector.app.features.session.coroutineScope
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.settings.devices.v2.notification.UpdateNotificationSettingsAccountDataUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.Session
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigureAndStartSessionUseCase @Inject constructor(
        @ApplicationContext private val context: Context,
        private val updateMatrixClientInfoUseCase: UpdateMatrixClientInfoUseCase,
        private val vectorPreferences: VectorPreferences,
        private val notificationsSettingUpdater: NotificationsSettingUpdater,
        private val updateNotificationSettingsAccountDataUseCase: UpdateNotificationSettingsAccountDataUseCase,
        private val pushRulesUpdater: PushRulesUpdater,
        private val accountInfoCache: AccountInfoCache,
        private val quickReactionsDataSource: QuickReactionsDataSource,
) {

    private val profileObserverJob = AtomicReference<Job?>(null)

    fun execute(session: Session, startSyncing: Boolean = true) {
        Timber.i("Configure and start session for ${session.myUserId}. startSyncing: $startSyncing")
        session.open()
        session.eventIndexService().setUnencryptedRoomsEnabled(!vectorPreferences.searchUnencryptedRoomsOnServer())
        session.eventIndexService().setEnabled(vectorPreferences.searchEncryptedRoomsEnabled())
        if (startSyncing) {
            session.startSyncing(context)
        }
        session.pushersService().refreshPushers()
        updateMatrixClientInfoIfNeeded(session)
        createNotificationSettingsAccountDataIfNeeded(session)
        notificationsSettingUpdater.onSessionStarted(session)
        pushRulesUpdater.onSessionStarted(session)
        quickReactionsDataSource.onSessionStarted(session)
        observeOwnProfileForCache(session)
    }

    private fun observeOwnProfileForCache(session: Session) {
        val newJob = session.coroutineScope.launch {
            // Initial seed — we own this regardless of whether the live data has emitted yet.
            accountInfoCache.storeForActive(session)
            session.userService().getUserLive(session.myUserId)
                    .asFlow()
                    .map { it.getOrNull()?.let { user -> user.displayName to user.avatarUrl } }
                    .distinctUntilChanged()
                    .drop(1) // skip the replay of the value we just seeded
                    .onEach { accountInfoCache.storeForActive(session) }
                    .retryWhen { cause, attempt ->
                        if (cause is CancellationException) return@retryWhen false
                        if (attempt >= PROFILE_OBSERVER_MAX_RETRIES) {
                            Timber.e(cause, "observeOwnProfileForCache: giving up after $attempt retries for ${session.myUserId}")
                            return@retryWhen false
                        }
                        Timber.w(cause, "observeOwnProfileForCache: transient error for ${session.myUserId}, retry ${attempt + 1}")
                        delay(PROFILE_OBSERVER_RETRY_MS)
                        true
                    }
                    .collect()
        }
        profileObserverJob.getAndSet(newJob)?.cancel()
    }

    fun cancelProfileObserver() {
        profileObserverJob.getAndSet(null)?.cancel()
    }

    companion object {
        private const val PROFILE_OBSERVER_RETRY_MS = 2_000L
        private const val PROFILE_OBSERVER_MAX_RETRIES = 5L
    }

    private fun updateMatrixClientInfoIfNeeded(session: Session) {
        session.coroutineScope.launch {
            if (vectorPreferences.isClientInfoRecordingEnabled()) {
                updateMatrixClientInfoUseCase.execute(session)
            }
        }
    }

    private fun createNotificationSettingsAccountDataIfNeeded(session: Session) {
        session.coroutineScope.launch {
            updateNotificationSettingsAccountDataUseCase.execute(session)
        }
    }
}
