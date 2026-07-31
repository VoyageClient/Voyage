/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.platform

import android.content.Context
import android.os.Build
import org.matrix.android.sdk.internal.network.FallbackNetworkCallbackStrategy
import org.matrix.android.sdk.internal.network.NetworkCallbackStrategy
import org.matrix.android.sdk.internal.network.NetworkInfoReceiver
import org.matrix.android.sdk.internal.network.PreferredNetworkCallbackStrategy

internal class AndroidNetworkCallbackStrategyFactory(
        private val context: Context,
) : NetworkCallbackStrategyFactory {

    override fun create(): NetworkCallbackStrategy {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PreferredNetworkCallbackStrategy(context)
        } else {
            FallbackNetworkCallbackStrategy(context, NetworkInfoReceiver())
        }
    }
}
