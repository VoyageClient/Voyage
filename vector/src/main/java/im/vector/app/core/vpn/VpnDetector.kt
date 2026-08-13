/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.getSystemService
import java.net.NetworkInterface
import javax.inject.Inject

class VpnDetector @Inject constructor(
        context: Context
) {
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!

    fun isVpnActive(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching {
                // Deprecated in 31, but the replacement (registerNetworkCallback tracking) can't
                // answer a synchronous "is any VPN up right now" snapshot
                @Suppress("DEPRECATION")
                connectivityManager.allNetworks.any { network ->
                    connectivityManager.getNetworkCapabilities(network)
                            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
            }.getOrDefault(false)
        } else {
            // No TRANSPORT_VPN before 21: an up tun*/ppp* interface is the VPN signal
            runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any {
                    it.isUp && (it.name.startsWith("tun") || it.name.startsWith("ppp"))
                }
            }.getOrDefault(false)
        }
    }
}
