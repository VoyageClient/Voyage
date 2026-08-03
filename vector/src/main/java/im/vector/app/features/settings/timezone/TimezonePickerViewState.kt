/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.timezone

import com.airbnb.mvrx.Async
import com.airbnb.mvrx.MavericksState
import com.airbnb.mvrx.Uninitialized

data class TimezonePickerViewState(
        val currentTimezoneId: String? = null,
        val filter: String = "",
        val timezones: Async<List<String>> = Uninitialized,
) : MavericksState
