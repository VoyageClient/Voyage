/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.session

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.vpn.VpnGateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.tryOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * While the app is in the foreground, periodically checks whether a homeserver mirror ranked above the one
 * in use is reachable again, so that a fallback does not outlive the outage that caused it.
 */
@Singleton
class HomeserverMirrorRefresher @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
        private val vpnGateState: VpnGateState,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onResume(owner: LifecycleOwner) {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                if (!vpnGateState.isClosed) {
                    activeSessionHolder.getSafeActiveSession()?.let {
                        tryOrNull { it.homeServerUrlsService().refreshActiveHomeServerUrl() }
                    }
                }
                delay(PROBE_INTERVAL_MILLIS)
            }
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        job?.cancel()
        job = null
    }

    companion object {
        private const val PROBE_INTERVAL_MILLIS = 5 * 60 * 1000L
    }
}
