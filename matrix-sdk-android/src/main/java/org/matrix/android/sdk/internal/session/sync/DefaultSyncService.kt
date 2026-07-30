/*
 * Copyright (c) 2022 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.sync

import org.matrix.android.sdk.api.session.sync.SyncService
import org.matrix.android.sdk.internal.di.SessionId
import org.matrix.android.sdk.internal.di.WorkManagerProvider
import org.matrix.android.sdk.internal.session.SessionState
import org.matrix.android.sdk.internal.session.sync.job.SyncThread
import org.matrix.android.sdk.internal.session.sync.job.SyncWorker
import org.matrix.android.sdk.internal.session.workmanager.WorkManagerConfig
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Provider

internal class DefaultSyncService @Inject constructor(
        @SessionId val sessionId: String,
        private val workManagerProvider: WorkManagerProvider,
        private val syncThreadProvider: Provider<SyncThread>,
        private val syncTokenStore: SyncTokenStore,
        private val syncRequestStateTracker: SyncRequestStateTracker,
        private val sessionState: SessionState,
        private val workManagerConfig: WorkManagerConfig,
) : SyncService {
    // Guards the lazy create/start/kill of [syncThread]: startSync() is reached from the main thread
    // (activity startup) and from background threads (foreground hook, session init) at the same time, and
    // an unguarded check-then-create there starts two sync threads that then both poll the homeserver.
    @Volatile
    private var syncThread: SyncThread? = null

    override fun requireBackgroundSync() {
        SyncWorker.requireBackgroundSync(
                workManagerProvider = workManagerProvider,
                sessionId = sessionId,
                workManagerConfig = workManagerConfig,
        )
    }

    override fun startAutomaticBackgroundSync(timeOutInSeconds: Long, repeatDelayInSeconds: Long) {
        SyncWorker.automaticallyBackgroundSync(
                workManagerProvider = workManagerProvider,
                sessionId = sessionId,
                workManagerConfig = workManagerConfig,
                serverTimeoutInSeconds = timeOutInSeconds,
                delayInSeconds = repeatDelayInSeconds,
        )
    }

    override fun stopAnyBackgroundSync() {
        SyncWorker.stopAnyBackgroundSync(workManagerProvider)
    }

    @Synchronized
    override fun startSync(fromForeground: Boolean) {
        assert(sessionState.isOpen)
        // A Thread cannot be started twice, so a terminated one has to be replaced rather than restarted.
        if ((getSyncThread() as Thread).state == Thread.State.TERMINATED) {
            Timber.w("Sync thread has terminated, recreating it")
            syncThread = null
        }
        val localSyncThread = getSyncThread()
        if (!localSyncThread.isAlive) {
            Timber.i("Starting sync thread")
            localSyncThread.setInitialForeground(fromForeground)
            localSyncThread.start()
        } else {
            // Do not call setInitialForeground here: it writes the live state from the caller's thread and
            // would flip a running sync to Paused. restart() is the idempotent "sync now" for a live thread.
            Timber.i("Sync thread already running, requesting an immediate sync")
            localSyncThread.restart()
        }
    }

    @Synchronized
    override fun stopSync() {
        assert(sessionState.isOpen)
        syncThread?.kill()
        syncThread = null
    }

    override fun getSyncStateLive() = getSyncThread().liveState()

    override fun syncFlow() = getSyncThread().syncFlow()

    override fun getSyncState() = getSyncThread().currentState()

    override fun isSyncThreadAlive() = getSyncThread().isAlive

    override fun getSyncRequestStateFlow() = syncRequestStateTracker.syncRequestState

    override fun hasAlreadySynced(): Boolean {
        return syncTokenStore.getLastToken() != null
    }

    @Synchronized
    private fun getSyncThread(): SyncThread {
        return syncThread ?: syncThreadProvider.get().also {
            syncThread = it
        }
    }
}
