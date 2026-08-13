/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.ActiveSessionDataSource
import im.vector.app.core.services.AlarmSyncBroadcastReceiver
import im.vector.app.core.session.LastActiveSessionStore
import im.vector.app.features.settings.VectorPreferences
import org.matrix.android.sdk.api.auth.AuthenticationService
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Controller of the VPN warning gate: watches VPN connectivity app-wide (also while backgrounded),
 * runs the acknowledge/invalidate state machine, and closes [VpnGateState] — halting all network —
 * whenever the VPN drops unacknowledged.
 */
@Singleton
class VpnGate @Inject constructor(
        @ApplicationContext private val context: Context,
        private val vpnGateState: VpnGateState,
        private val vpnDetector: VpnDetector,
        private val vectorPreferences: VectorPreferences,
        private val authenticationService: AuthenticationService,
        private val lastActiveSessionStore: LastActiveSessionStore,
        private val activeSessionDataSource: ActiveSessionDataSource,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var monitorRegistered = false

    private val networkCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        object : ConnectivityManager.NetworkCallback() {
            // Single events can be stale or partial (multiple VPN networks): always re-snapshot
            override fun onAvailable(network: Network) {
                mainHandler.post { refreshSnapshot() }
            }

            override fun onLost(network: Network) {
                mainHandler.post { refreshSnapshot() }
            }
        }
    } else null

    private val connectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshSnapshot()
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            VectorPreferences.SETTINGS_VPN_WARN_ON_LAUNCH_KEY -> onLaunchToggleChanged()
            VectorPreferences.SETTINGS_VPN_EXCLUDED_SESSION_IDS_KEY -> recompute()
        }
    }

    /** Called once from Application.onCreate, before any activity can resume. */
    fun start() {
        vectorPreferences.subscribeToChanges(prefListener)
        if (vectorPreferences.isVpnWarnOnLaunchEnabled()) {
            registerMonitor()
        }
        refreshSnapshot()
    }

    fun onAppForegrounded() {
        refreshSnapshot()
    }

    /** User tapped Proceed on the warning, or confirmed a VPN-off account switch. */
    fun acknowledge() {
        vectorPreferences.setVpnOffAcknowledged(true)
        recompute()
    }

    /** Forces the gate to re-prompt on the next launch (logout auto-switch). */
    fun clearAcknowledgement() {
        vectorPreferences.setVpnOffAcknowledged(false)
    }

    fun refreshSnapshot() {
        if (!vectorPreferences.isVpnWarnOnLaunchEnabled()) {
            recompute()
            return
        }
        val vpnOn = vpnDetector.isVpnActive()
        val wasOn = vectorPreferences.wasVpnLastObservedOn()
        if (vpnOn) {
            // Only a VPN-ON observation invalidates a previous acknowledgement
            vectorPreferences.setVpnOffAcknowledged(false)
        } else if (wasOn) {
            Timber.i("VpnGate: VPN on -> off transition observed")
            vectorPreferences.setVpnOffAcknowledged(false)
        }
        if (vpnOn != wasOn) {
            vectorPreferences.setVpnLastObservedOn(vpnOn)
        }
        recompute()
    }

    @Synchronized
    fun recompute() {
        val closed = vectorPreferences.isVpnWarnOnLaunchEnabled() &&
                authenticationService.hasAuthenticatedSessions() &&
                lastActiveSessionStore.get() !in vectorPreferences.getVpnExcludedSessionIds() &&
                !vpnDetector.isVpnActive() &&
                !vectorPreferences.isVpnOffAcknowledged()
        val wasClosed = vpnGateState.isClosed
        if (closed == wasClosed) return
        Timber.i("VpnGate: ${if (closed) "closing" else "opening"} gate")
        if (closed) stopNetwork()
        vpnGateState.publishClosed(closed)
    }

    private fun stopNetwork() {
        activeSessionDataSource.currentValue?.orNull()?.let { session ->
            runCatching { session.syncService().stopAnyBackgroundSync() }
            runCatching { session.syncService().stopSync() }
        }
        AlarmSyncBroadcastReceiver.cancelPendingAlarm(context)
    }

    private fun onLaunchToggleChanged() {
        if (vectorPreferences.isVpnWarnOnLaunchEnabled()) {
            vectorPreferences.setVpnOffAcknowledged(false)
            registerMonitor()
            refreshSnapshot()
        } else {
            unregisterMonitor()
            recompute()
        }
    }

    private fun registerMonitor() {
        if (monitorRegistered) return
        monitorRegistered = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    // Default requests require NOT_VPN, which would never match a VPN network
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build()
            runCatching {
                context.getSystemService<ConnectivityManager>()!!.registerNetworkCallback(request, networkCallback!!)
            }.onFailure { Timber.e(it, "VpnGate: failed to register network callback") }
        } else {
            @Suppress("DEPRECATION")
            ContextCompat.registerReceiver(
                    context,
                    connectivityReceiver,
                    IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION),
                    ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    private fun unregisterMonitor() {
        if (!monitorRegistered) return
        monitorRegistered = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching { context.getSystemService<ConnectivityManager>()!!.unregisterNetworkCallback(networkCallback!!) }
        } else {
            runCatching { context.unregisterReceiver(connectivityReceiver) }
        }
    }
}
