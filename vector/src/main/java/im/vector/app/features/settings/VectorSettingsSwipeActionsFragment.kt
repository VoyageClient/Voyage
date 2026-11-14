/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings

import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

@AndroidEntryPoint
class VectorSettingsSwipeActionsFragment : VectorSettingsBaseFragment() {
    @Inject lateinit var vectorPreferences: VectorPreferences

    override var titleRes = CommonStrings.settings_swipe_actions
    override val preferenceXmlRes = R.xml.vector_settings_swipe_actions

    override fun bindPref() {
        MessageSwipePreferenceBinder(this, vectorPreferences).bind()
    }
}
