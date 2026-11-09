/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.platform

import org.matrix.android.sdk.internal.network.NetworkCallbackStrategy

/**
 * Network-reachability change notifications; picks a ConnectivityManager-based strategy by API level.
 */
internal interface NetworkCallbackStrategyFactory {

    fun create(): NetworkCallbackStrategy
}
