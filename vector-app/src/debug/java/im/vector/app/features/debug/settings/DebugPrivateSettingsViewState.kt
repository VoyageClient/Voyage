/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.debug.settings

import com.airbnb.mvrx.MavericksState

data class DebugPrivateSettingsViewState(
        val dialPadVisible: Boolean = false,
        val forceLoginFallback: Boolean = false,
        val forceIdentityChangeBanner: Boolean = false,
) : MavericksState
