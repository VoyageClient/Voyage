/*
 * Copyright 2024 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.desktop.platform

import org.matrix.android.sdk.internal.network.NetworkCallbackStrategy
import org.matrix.android.sdk.internal.platform.NetworkCallbackStrategyFactory

/**
 * Desktop network strategy: assume always-online. There is no desktop equivalent of Android's
 * ConnectivityManager broadcast, and the SDK polls the homeserver for availability anyway, so this
 * never fires a change callback.
 */
internal class AssumeOnlineNetworkCallbackStrategyFactory : NetworkCallbackStrategyFactory {
    override fun create(): NetworkCallbackStrategy = AssumeOnlineNetworkCallbackStrategy
}

internal object AssumeOnlineNetworkCallbackStrategy : NetworkCallbackStrategy {
    override fun register(hasChanged: () -> Unit) = Unit
    override fun unregister() = Unit
}
