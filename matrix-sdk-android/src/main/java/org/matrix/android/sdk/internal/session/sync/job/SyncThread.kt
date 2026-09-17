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
 */

package org.matrix.android.sdk.internal.session.sync.job

import android.os.SystemClock
import com.squareup.moshi.JsonEncodingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.matrix.android.sdk.api.MatrixConfiguration
import org.matrix.android.sdk.api.debug.SyncDebugFlags
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.isTokenError
import org.matrix.android.sdk.api.logger.LoggerTag
import org.matrix.android.sdk.api.session.sync.SyncState
import org.matrix.android.sdk.api.session.sync.model.SyncResponse
import org.matrix.android.sdk.internal.network.NetworkConnectivityChecker
import org.matrix.android.sdk.internal.session.call.ActiveCallHandler
import org.matrix.android.sdk.internal.session.sync.SyncTask
import org.matrix.android.sdk.internal.session.sync.sliding.SlidingSyncRoomSubscriptions
import org.matrix.android.sdk.internal.settings.DefaultLightweightSettingsStorage
import org.matrix.android.sdk.internal.util.BackgroundDetectionObserver
import timber.log.Timber
import java.net.SocketTimeoutException
import java.util.Timer
import java.util.TimerTask
import javax.inject.Inject
import kotlin.concurrent.schedule

private const val RETRY_WAIT_TIME_MS = 10_000L

private const val MIN_AGE_TO_CANCEL_SYNC_MS = 5_000L

// How long a resume may keep requesting without a long poll before it is treated as ordinary traffic.
private const val MAX_CATCH_UP_MS = 60_000L

private val loggerTag = LoggerTag("SyncThread", LoggerTag.SYNC)

internal class SyncThread @Inject constructor(
        private val syncTask: SyncTask,
        private val networkConnectivityChecker: NetworkConnectivityChecker,
        private val backgroundDetectionObserver: BackgroundDetectionObserver,
        private val activeCallHandler: ActiveCallHandler,
        private val lightweightSettingsStorage: DefaultLightweightSettingsStorage,
        private val matrixConfiguration: MatrixConfiguration,
        private val syncImportState: org.matrix.android.sdk.internal.session.sync.SyncImportState,
        private val syncStateHolder: org.matrix.android.sdk.internal.session.sync.SyncStateHolder,
        private val slidingSyncRoomSubscriptions: SlidingSyncRoomSubscriptions,
) : Thread("Matrix-SyncThread"), NetworkConnectivityChecker.Listener, BackgroundDetectionObserver.Listener {

    private var state: SyncState = SyncState.Idle
    private val lock = Object()
    private val syncScope = CoroutineScope(SupervisorJob())

    private var canReachServer = true
    private var isStarted = false
    private var isTokenValid = true
    private var retryNoNetworkTask: TimerTask? = null
    private var previousSyncResponseHasToDevice = false

    // Reference to the currently-running sync coroutine so we can cancel it from
    // foreground/connectivity callbacks. Without this, a long-poll request on a
    // silently-dead TCP socket (Wi-Fi sleep, NAT timeout after hours backgrounded)
    // blocks the run loop in `runBlocking { sync.join() }` until OkHttp's read timeout
    // fires — observed as "sync doesn't resume until I force-close and reopen".
    @Volatile
    private var inflightSyncJob: Job? = null

    // Identifies the in-flight request so a burst of restart()/onConnectivityChanged() callbacks kicks a
    // given request at most once, instead of tearing down the healthy replacement the previous kick started.
    @Volatile
    private var inflightSyncGeneration = 0L
    @Volatile
    private var cancelledSyncGeneration = -1L
    @Volatile
    private var inflightSyncStartedAt = 0L

    // `pause()` does not interrupt the run loop, so when the app is backgrounded mid-request (and the
    // device then dozes, freezing that request for the whole background period) the loop never reaches
    // the Paused branch and `state` stays Running(afterPause = false). Without this flag the request
    // issued on the next foreground would inherit afterPause = false: a silent 30s long poll with no
    // progress bar, instead of the immediate catch-up the user is waiting for.
    @Volatile
    private var forceImmediateSync = false

    // Same "don't long poll, ask now" effect, but without claiming a catch-up: opening a room changes the
    // sliding-sync subscriptions several times, and routing that through forceImmediateSync relit the
    // progress bar on every one of them.
    @Volatile
    private var forceQuietImmediateSync = false

    private var catchUpStartedAt = 0L

    private var activeCallsJob: Job? = null
    private val roomSubscriptionsListener: () -> Unit = { requestImmediateSyncIfRunning() }

    private val _syncFlow = MutableSharedFlow<SyncResponse>()

    init {
        updateStateTo(SyncState.Idle)
    }

    fun setInitialForeground(initialForeground: Boolean) {
        val newState = if (initialForeground) SyncState.Idle else SyncState.Paused
        updateStateTo(newState)
    }

    fun restart() = synchronized(lock) {
        forceImmediateSync = true
        if (!isStarted) {
            Timber.tag(loggerTag.value).d("Resume sync...")
            isStarted = true
            // Check again server availability and the token validity
            canReachServer = true
            isTokenValid = true
            lock.notify()
        }
        // Kick the in-flight sync. If the request is still healthy it will be re-issued
        // immediately using the saved since-token (no data loss, sync is idempotent on the
        // same token). If the underlying socket was wedged, this unblocks the loop right
        // now instead of after the OkHttp read timeout.
        cancelInflightSync("restart")
    }

    fun pause() = synchronized(lock) {
        if (isStarted) {
            Timber.tag(loggerTag.value).d("Pause sync...")
            isStarted = false
            forceImmediateSync = true
            retryNoNetworkTask?.cancel()
            cancelInflightSync("backgrounded", force = true)
        }
    }

    private fun requestImmediateSyncIfRunning() = synchronized(lock) {
        if (!isStarted) return@synchronized
        forceQuietImmediateSync = true
        cancelInflightSync("room-subscriptions-changed", force = true)
    }

    fun kill() = synchronized(lock) {
        Timber.tag(loggerTag.value).d("Kill sync...")
        updateStateTo(SyncState.Killing)
        retryNoNetworkTask?.cancel()
        syncScope.coroutineContext.cancelChildren()
        lock.notify()
    }

    fun syncFlow(): SharedFlow<SyncResponse> = _syncFlow

    override fun onConnectivityChanged() {
        retryNoNetworkTask?.cancel()
        synchronized(lock) {
            // Only force a catch-up when recovering from an actual outage. The checker also reports the
            // current network on every resume, where the loop is already issuing one.
            if (!canReachServer) forceImmediateSync = true
            canReachServer = true
            lock.notify()
        }
        // Connectivity just changed — any in-flight sync was probably bound to the previous
        // network state and will hang until its read timeout. Cancel so the next iteration
        // opens a fresh request on the new connection.
        cancelInflightSync("connectivity-changed")
    }

    private fun cancelInflightSync(reason: String, force: Boolean = false) {
        val job = inflightSyncJob ?: return
        val generation = inflightSyncGeneration
        if (!job.isActive || generation == cancelledSyncGeneration) return
        // Only a request old enough to have been stranded by the event is worth kicking. The connectivity
        // checker reports the network once at startup, milliseconds after the first request goes out —
        // cancelling that one just throws away a healthy sync and re-issues it.
        if (!force && SystemClock.elapsedRealtime() - inflightSyncStartedAt < MIN_AGE_TO_CANCEL_SYNC_MS) {
            Timber.tag(loggerTag.value).d("Not cancelling a just-issued sync ($reason)")
            return
        }
        // Cancelling during import would discard work and replay the same delta.
        if (syncImportState.isImporting) {
            Timber.tag(loggerTag.value).d("Not cancelling a sync that is importing its response ($reason)")
            return
        }
        cancelledSyncGeneration = generation
        Timber.tag(loggerTag.value).d("Cancelling in-flight sync ($reason)")
        job.cancel(CancellationException("Sync cancelled: $reason"))
    }

    override fun run() {
        Timber.tag(loggerTag.value).d("Start syncing...")

        isStarted = true
        networkConnectivityChecker.register(this)
        backgroundDetectionObserver.register(this)
        slidingSyncRoomSubscriptions.addListener(roomSubscriptionsListener)
        registerActiveCallsObserver()
        while (state != SyncState.Killing) {
            Timber.tag(loggerTag.value).d("Entering loop, state: $state")
            if (!isStarted) {
                Timber.tag(loggerTag.value).d("Sync is Paused. Waiting...")
                updateStateTo(SyncState.Paused)
                synchronized(lock) { lock.wait() }
                Timber.tag(loggerTag.value).d("...unlocked")
            } else if (!canReachServer) {
                Timber.tag(loggerTag.value).d("No network. Waiting...")
                updateStateTo(SyncState.NoNetwork)
                // We force retrying in RETRY_WAIT_TIME_MS maximum. Otherwise it will be unlocked by onConnectivityChanged() or restart()
                retryNoNetworkTask = Timer(SyncState.NoNetwork.toString(), false).schedule(RETRY_WAIT_TIME_MS) {
                    synchronized(lock) {
                        canReachServer = true
                        lock.notify()
                    }
                }
                synchronized(lock) { lock.wait() }
                Timber.tag(loggerTag.value).d("...retry")
            } else if (!isTokenValid) {
                if (state == SyncState.Killing) {
                    continue
                }
                Timber.tag(loggerTag.value).d("Token is invalid. Waiting...")
                updateStateTo(SyncState.InvalidToken)
                synchronized(lock) { lock.wait() }
                Timber.tag(loggerTag.value).d("...unlocked")
            } else {
                if (forceImmediateSync || state !is SyncState.Running) {
                    forceImmediateSync = false
                    if (state.let { it !is SyncState.Running || !it.afterPause }) {
                        catchUpStartedAt = SystemClock.elapsedRealtime()
                    }
                    updateStateTo(SyncState.Running(afterPause = true))
                }
                val quietImmediate = forceQuietImmediateSync
                forceQuietImmediateSync = false
                val afterPause = state.let { it is SyncState.Running && it.afterPause }
                val timeout = when {
                    previousSyncResponseHasToDevice -> 0L /* Force timeout to 0 */
                    afterPause || quietImmediate -> 0L /* No timeout after a pause */
                    else -> matrixConfiguration.syncConfig.longPollTimeout
                }
                Timber.tag(loggerTag.value).d("Execute sync request with timeout $timeout")
                val presence = lightweightSettingsStorage.getSyncPresenceStatus()
                val params = SyncTask.Params(timeout, presence, afterPause = afterPause)
                val sync = syncScope.launch {
                    previousSyncResponseHasToDevice = doSync(params)
                }
                synchronized(lock) {
                    inflightSyncGeneration++
                    inflightSyncStartedAt = SystemClock.elapsedRealtime()
                    inflightSyncJob = sync
                }
                runBlocking {
                    sync.join()
                }
                inflightSyncJob = null
                Timber.tag(loggerTag.value).d("...Continue")
            }
        }
        Timber.tag(loggerTag.value).d("Sync killed")
        updateStateTo(SyncState.Killed)
        backgroundDetectionObserver.unregister(this)
        slidingSyncRoomSubscriptions.removeListener(roomSubscriptionsListener)
        networkConnectivityChecker.unregister(this)
        unregisterActiveCallsObserver()
    }

    private fun registerActiveCallsObserver() {
        activeCallsJob = syncScope.launch(Dispatchers.Main) {
            activeCallHandler.getActiveCallsFlow().collect { activeCalls ->
                if (activeCalls.isEmpty() && backgroundDetectionObserver.isInBackground && !SyncDebugFlags.keepSyncingInBackground) {
                    pause()
                }
            }
        }
    }

    private fun unregisterActiveCallsObserver() {
        activeCallsJob?.cancel()
        activeCallsJob = null
    }

    /**
     * Will return true if the sync response contains some toDevice events.
     */
    private suspend fun doSync(params: SyncTask.Params): Boolean {
        return try {
            val syncResponse = syncTask.execute(params)
            _syncFlow.emit(syncResponse)
            state.let {
                // A catch-up is not one request: the server hands back the backlog a response at a time, so
                // dropping out after the first one hid the progress bar for the rest of it — the case the
                // user sees as "it synced for 20s without a bar". Stay in catch-up until a response brings
                // nothing more, with a time bound so a busy account cannot hold it open indefinitely.
                val stillCatchingUp = syncResponse.hasRoomUpdates() &&
                        SystemClock.elapsedRealtime() - catchUpStartedAt < MAX_CATCH_UP_MS
                if (it is SyncState.Running && it.afterPause && !syncImportState.catchUpPending && !stillCatchingUp) {
                    updateStateTo(SyncState.Running(afterPause = false))
                }
            }
            syncResponse.toDevice?.events?.isNotEmpty().orFalse()
        } catch (failure: Throwable) {
            if (failure is Failure.NetworkConnection) {
                canReachServer = false
            }
            if (failure is Failure.NetworkConnection && failure.cause is SocketTimeoutException) {
                // Timeout are not critical
                Timber.tag(loggerTag.value).d("Timeout")
            } else if (failure is CancellationException) {
                Timber.tag(loggerTag.value).d("Cancelled")
            } else if (failure.isTokenError()) {
                // No token or invalid token, stop the thread
                Timber.tag(loggerTag.value).w(failure, "Token error")
                isStarted = false
                isTokenValid = false
            } else {
                Timber.tag(loggerTag.value).e(failure)
                if (failure !is Failure.NetworkConnection || failure.cause is JsonEncodingException) {
                    // Wait 10s before retrying
                    Timber.tag(loggerTag.value).d("Wait 10s")
                    delay(RETRY_WAIT_TIME_MS)
                }
            }
            false
        }
    }

    private fun updateStateTo(newState: SyncState) {
        Timber.tag(loggerTag.value).d("Update state from $state to $newState")
        if (newState == state) {
            return
        }
        state = newState
        // Publishing straight from the sync thread: hopping through a main-thread Handler meant a sync
        // shorter than the debounce window never published its Running(afterPause = true) at all (the
        // following state cancelled it), and a congested main thread published it seconds late.
        syncStateHolder.state.value = newState
    }

    override fun onMoveToForeground() {
        restart()
    }

    override fun onMoveToBackground() {
        if (SyncDebugFlags.keepSyncingInBackground) {
            Timber.tag(loggerTag.value).i("Backgrounded, but debug background sync is on: staying live")
            return
        }
        if (activeCallHandler.getActiveCallsFlow().value.isEmpty()) {
            pause()
        }
    }
}

private fun SyncResponse.hasRoomUpdates(): Boolean =
        rooms?.let { it.join.isNotEmpty() || it.invite.isNotEmpty() || it.leave.isNotEmpty() || it.knock.isNotEmpty() }.orFalse()
