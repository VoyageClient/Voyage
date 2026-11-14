/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.federation

object ServerName {
    const val DEFAULT_FEDERATION_PORT = 8448

    /** The server name without its port, keeping an IPv6 literal's brackets. */
    fun hostOf(serverName: String): String =
            if (hasExplicitPort(serverName)) serverName.substringBeforeLast(':') else serverName

    fun hasExplicitPort(serverName: String): Boolean {
        val tail = serverName.substringAfterLast(':', missingDelimiterValue = "")
        // In an IPv6 literal only a colon after the closing bracket starts a port.
        return tail.isNotEmpty() && tail.all { it.isDigit() } &&
                (!serverName.startsWith("[") || serverName.substringBeforeLast(':').endsWith("]"))
    }

    fun isIpLiteral(host: String) = host.startsWith("[") || host.all { it.isDigit() || it == '.' }
}
