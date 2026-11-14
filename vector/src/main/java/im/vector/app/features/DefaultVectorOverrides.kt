/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface VectorOverrides {
    val forceDialPad: Flow<Boolean>
    val forceLoginFallback: Flow<Boolean>
    val forceIdentityChangeBanner: Flow<Boolean>
}

class DefaultVectorOverrides : VectorOverrides {
    override val forceDialPad = flowOf(false)
    override val forceLoginFallback = flowOf(false)
    override val forceIdentityChangeBanner = flowOf(false)
}
