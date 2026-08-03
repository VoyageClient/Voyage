/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.timezone

import im.vector.app.core.platform.VectorViewModelAction

sealed class TimezonePickerAction : VectorViewModelAction {
    data class SelectTimezone(val id: String) : TimezonePickerAction()
    object ClearTimezone : TimezonePickerAction()
    data class UpdateFilter(val filter: String) : TimezonePickerAction()
}
