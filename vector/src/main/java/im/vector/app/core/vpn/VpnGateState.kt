/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.vpn

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the "network allowed?" state of the VPN gate. Kept free of session/holder dependencies so
 * it can be injected anywhere (including into the OkHttp interceptor and ActiveSessionHolder)
 * without dependency cycles; [VpnGate] drives the transitions.
 */
@Singleton
class VpnGateState @Inject constructor() {

    @Volatile
    var isClosed: Boolean = false
        private set

    private val _liveClosed = MutableLiveData(false)
    val liveClosed: LiveData<Boolean> = _liveClosed

    private val deferredActions = ArrayList<Pair<String?, () -> Unit>>()

    fun publishClosed(closed: Boolean) {
        isClosed = closed
        _liveClosed.postValue(closed)
        if (!closed) drainQueue()
    }

    /**
     * Runs [block] now if the gate is open, otherwise queues it until the gate opens.
     * Queued blocks tagged with a [sessionId] are dropped when that session is torn down.
     */
    fun runWhenOpen(sessionId: String? = null, block: () -> Unit) {
        val runNow = synchronized(deferredActions) {
            if (isClosed) {
                deferredActions.add(sessionId to block)
                false
            } else {
                true
            }
        }
        if (runNow) block()
    }

    fun dropQueuedFor(sessionId: String) {
        synchronized(deferredActions) {
            deferredActions.removeAll { it.first == sessionId }
        }
    }

    private fun drainQueue() {
        val toRun = synchronized(deferredActions) {
            val copy = deferredActions.toList()
            deferredActions.clear()
            copy
        }
        if (toRun.isNotEmpty()) Timber.i("VpnGate: draining ${toRun.size} deferred action(s)")
        toRun.forEach { (_, block) -> block() }
    }
}
