/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.vpn

import android.os.Handler
import android.os.Looper
import im.vector.app.core.session.LastActiveSessionStore
import im.vector.app.features.settings.VectorPreferences
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider

/**
 * Backstop for the zero-network guarantee: fails every SDK HTTP request while the VPN gate is
 * closed. Covers paths that bypass the app-level checkpoints (e.g. SDK workers rehydrated from
 * persisted WorkManager data). Wired via MatrixConfiguration.networkInterceptors.
 */
class VpnGateInterceptor @Inject constructor(
        private val vpnGateState: VpnGateState,
        private val vpnDetector: VpnDetector,
        private val vectorPreferences: VectorPreferences,
        private val lastActiveSessionStore: LastActiveSessionStore,
        // Provider: VpnGate reaches Matrix-provided types, and this interceptor feeds the
        // MatrixConfiguration that builds Matrix — direct injection would cycle
        private val vpnGate: Provider<VpnGate>,
) : Interceptor {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun intercept(chain: Interceptor.Chain): Response {
        if (vpnGateState.isClosed) {
            block(chain)
        }
        // The VPN-lost callback can lag the actual drop by seconds; while protection is armed,
        // sample the kernel state directly so a request issued inside that window can't slip
        // through on the bare network. lastActiveSessionStore null = logged out, never gate login.
        if (vectorPreferences.isVpnWarnOnLaunchEnabled() &&
                !vectorPreferences.isVpnOffAcknowledged() &&
                lastActiveSessionStore.get()?.let { it !in vectorPreferences.getVpnExcludedSessionIds() } == true &&
                !vpnDetector.isVpnActive()) {
            // Run the full drop reaction (close gate, stop sync, show the warning)
            mainHandler.post { vpnGate.get().refreshSnapshot() }
            block(chain)
        }
        return chain.proceed(chain.request())
    }

    private fun block(chain: Interceptor.Chain): Nothing {
        Timber.w("VpnGate: network blocked for ${chain.request().url().host()}")
        throw IOException("VpnGate: network blocked until VPN warning acknowledged")
    }
}
