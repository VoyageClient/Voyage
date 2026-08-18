/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.media

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Resolves to public addresses only, so that a link preview cannot be used to reach the phone's own
 * network — a public name is allowed to point at 127.0.0.1 or 192.168.x.x, which is why MSC4095 asks
 * clients to check the resolved addresses rather than the hostname. Applied as a [Dns], so redirects
 * are checked as well as the original request.
 */
internal class PublicHostDns(private val delegate: Dns = Dns.SYSTEM) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname).filter { it.isPubliclyRoutable() }
        if (addresses.isEmpty()) {
            throw UnknownHostException("$hostname does not resolve to a public address")
        }
        return addresses
    }
}

internal fun InetAddress.isPubliclyRoutable(): Boolean {
    if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) {
        return false
    }
    val bytes = address
    return when (bytes.size) {
        // 100.64.0.0/10, the carrier grade NAT range, which java.net does not know about.
        4 -> !(bytes[0].toInt() and 0xFF == 100 && (bytes[1].toInt() and 0xFF) in 64..127)
        // fc00::/7, unique local addresses: the IPv6 equivalent of a private range, and likewise unknown to java.net.
        16 -> bytes[0].toInt() and 0xFE != 0xFC
        else -> true
    }
}
