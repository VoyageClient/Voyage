/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.platform

import org.matrix.android.sdk.internal.network.NetworkCallbackStrategy

/**
 * Platform seam for network-reachability change notifications. Android picks a
 * ConnectivityManager-based strategy by API level; a desktop implementation can assume-online
 * (the sync loop and send queue already recover via homeserver pings and backoff).
 */
internal interface NetworkCallbackStrategyFactory {

    fun create(): NetworkCallbackStrategy
}
