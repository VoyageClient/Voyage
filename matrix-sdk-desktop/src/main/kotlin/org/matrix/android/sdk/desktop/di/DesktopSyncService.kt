/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.desktop.di

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.session.sync.SyncRequestState
import org.matrix.android.sdk.api.session.sync.SyncService
import org.matrix.android.sdk.api.session.sync.SyncState
import org.matrix.android.sdk.api.session.sync.model.SyncResponse
import org.matrix.android.sdk.internal.session.sync.SyncPresence
import org.matrix.android.sdk.internal.session.sync.SyncRequestStateTracker
import org.matrix.android.sdk.internal.session.sync.SyncTask
import org.matrix.android.sdk.internal.session.sync.SyncTokenStore
import org.matrix.android.sdk.internal.session.sync.sliding.SlidingSyncRequiredState
import timber.log.Timber
import javax.inject.Inject

/**
 * The desktop sync loop: what SyncThread does on android, as a coroutine. There is no app
 * foreground/background here, so the loop only ever runs or stops, and the "background sync"
 * scheduling calls are no-ops.
 */
internal class DesktopSyncService @Inject constructor(
        private val syncTask: SyncTask,
        private val syncTokenStore: SyncTokenStore,
        private val syncRequestStateTracker: SyncRequestStateTracker,
        coroutineDispatchers: MatrixCoroutineDispatchers,
) : SyncService {

    private val scope = CoroutineScope(SupervisorJob() + coroutineDispatchers.io)
    private val state = MutableStateFlow<SyncState>(SyncState.Idle)
    private val responses = MutableSharedFlow<SyncResponse>(replay = 0, extraBufferCapacity = 10)

    @Volatile
    private var loop: Job? = null

    @Synchronized
    override fun startSync(fromForeground: Boolean) {
        if (loop?.isActive == true) return
        loop = scope.launch {
            var afterPause = true
            while (isActive) {
                state.value = SyncState.Running(afterPause)
                try {
                    val response = syncTask.execute(SyncTask.Params(TIMEOUT_MILLIS, SyncPresence.Online, afterPause))
                    responses.emit(response)
                    afterPause = false
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    Timber.e(throwable, "Sync failed, retrying")
                    state.value = SyncState.NoNetwork
                    delay(RETRY_DELAY_MILLIS)
                    afterPause = true
                }
            }
        }
    }

    @Synchronized
    override fun stopSync() {
        loop?.cancel()
        loop = null
        state.value = SyncState.Idle
    }

    override fun requireBackgroundSync() = Unit

    override fun startAutomaticBackgroundSync(timeOutInSeconds: Long, repeatDelayInSeconds: Long) = Unit

    override fun stopAnyBackgroundSync() = Unit

    override fun getSyncState(): SyncState = state.value

    override fun isSyncThreadAlive(): Boolean = loop?.isActive == true

    override fun getSyncStateFlow(): StateFlow<SyncState> = state

    override fun getSyncRequestStateFlow(): SharedFlow<SyncRequestState> = syncRequestStateTracker.syncRequestState

    override fun syncFlow(): SharedFlow<SyncResponse> = responses

    override fun hasAlreadySynced(): Boolean {
        val slidingSynced = syncTokenStore.getSlidingSyncPos() != null &&
                syncTokenStore.getSlidingSyncStateVersion() == SlidingSyncRequiredState.VERSION
        return syncTokenStore.getLastToken() != null || slidingSynced
    }

    companion object {
        private const val TIMEOUT_MILLIS = 30_000L
        private const val RETRY_DELAY_MILLIS = 5_000L
    }
}
